package app.thoughtcapturer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

// Same Azure app registration as canvas-todo-sync, so To Do already trusts it.
const val MS_CLIENT_ID = "d399042a-c6a4-4b4e-9d71-2349c92b760a"
const val MS_SCOPE = "Tasks.ReadWrite Notes.Create offline_access"
const val MS_LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0"
const val TODO_LISTS = "https://graph.microsoft.com/v1.0/me/todo/lists"
// sectionName: OneNote finds or creates this section in the default notebook
const val ONENOTE_PAGES = "https://graph.microsoft.com/v1.0/me/onenote/pages?sectionName=Capture"
// shortcut: the alias follows Google's newest Flash model; pin a version if its output ever drifts.
const val GEMINI_MODEL = "gemini-flash-latest"

fun Context.prefs(): SharedPreferences = getSharedPreferences("capture", Context.MODE_PRIVATE)

/**
 * [remind] is a local time, yyyy-MM-ddTHH:mm:ss, when To Do should alert; [important] stars the task; [steps] are its
 * To Do checklist, the logistics said around it.
 */
data class Task(val title: String, val due: String?, val remind: String? = null, val important: Boolean = false, val steps: List<String> = emptyList())
/** [later]: things said as maybes ("maybe I should..."), parked here instead of becoming tasks. */
data class Note(val title: String, val points: List<String>, val later: List<String> = emptyList())
data class Split(val tasks: List<Task>, val note: Note?)

private val REMINDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it).trim() }.filter { it.isNotEmpty() }

/** Gemini's JSON to tasks and an optional note. Anything unusable falls back to the raw capture as one task. */
fun parseSplit(json: String?, raw: String): Split {
    val parsed = runCatching {
        val o = JSONObject(json ?: "")
        val a = o.optJSONArray("tasks") ?: JSONArray()
        val tasks = (0 until a.length()).map { a.getJSONObject(it) }
            // a real date and time only: To Do rejects "2027-02-29", and that would fail the upload forever
            .map {
                Task(
                    it.optString("title").trim(),
                    it.optString("due").takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess },
                    runCatching { LocalDateTime.parse(it.optString("remind")).format(REMINDER_TIME) }.getOrNull(),
                    it.optBoolean("important"),
                    strings(it.optJSONArray("steps")),
                )
            }
            .filter { it.title.isNotEmpty() }
        val n = o.optJSONObject("note")
        val points = strings(n?.optJSONArray("points"))
        val later = strings(o.optJSONArray("later"))
        val title = n?.optString("title")?.trim().orEmpty().ifEmpty { if (points.isEmpty()) "Later" else "Capture" }
        Split(tasks, if (points.isEmpty() && later.isEmpty()) null else Note(title, points, later))
    }.getOrNull()
    val split = parsed?.takeIf { it.tasks.isNotEmpty() || it.note != null } ?: Split(listOf(Task(raw, null)), null)
    // To Do caps titles at 255 chars; the full capture also goes in the task note
    return split.copy(tasks = split.tasks.map { it.copy(title = it.title.take(255), steps = it.steps.map { s -> s.take(255) }) })
}

/** One Graph call of a capture's upload. */
sealed interface Upload {
    data class ToDo(val task: Int) : Upload
    data class Step(val task: Int, val step: Int) : Upload
    data object Page : Upload
}

/** Every call a split needs, in order: each task and then its steps, then the OneNote page. */
fun uploads(split: Split): List<Upload> =
    split.tasks.flatMapIndexed { i, t -> listOf(Upload.ToDo(i)) + t.steps.indices.map { Upload.Step(i, it) } } +
        listOfNotNull(Upload.Page.takeIf { split.note != null })

/** The OneNote page. Everything is escaped: a stray "<" or "&" in speech would get the page rejected and stall the upload. */
fun noteHtml(note: Note, raw: String, at: Long): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun list(items: List<String>) = if (items.isEmpty()) "" else "<ul>${items.joinToString("") { "<li>${esc(it)}</li>" }}</ul>"
    val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(at)) // the time you said it, not the upload time
    return "<!DOCTYPE html><html><head><title>${esc(note.title)}</title><meta name=\"created\" content=\"$created\" /></head><body>" +
        list(note.points) + (if (note.later.isEmpty()) "" else "<p><b>Later</b></p>" + list(note.later)) +
        "<p><i>What you said:</i> ${esc(raw)}</p></body></html>"
}

/** The To Do task to create. [tz] is a Windows zone name; [raw] goes in the body unless it's the title already. */
fun taskJson(t: Task, raw: String, tz: String): JSONObject {
    val task = JSONObject().put("title", t.title)
    if (t.title != raw) task.put("body", JSONObject().put("content", raw).put("contentType", "text"))
    if (t.due != null) task.put("dueDateTime", JSONObject().put("dateTime", "${t.due}T00:00:00").put("timeZone", tz))
    if (t.remind != null) task.put("isReminderOn", true).put("reminderDateTime", JSONObject().put("dateTime", t.remind).put("timeZone", tz))
    if (t.important) task.put("importance", "high")
    return task
}

class HttpError(val code: Int, val body: String) : IOException("HTTP $code ${body.take(300)}")

fun http(url: String, body: String? = null, type: String = "application/json", headers: Map<String, String> = emptyMap(), readTimeout: Int = 60_000): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = readTimeout
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    try {
        if (body != null) {
            c.doOutput = true // makes it a POST
            c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) throw HttpError(c.responseCode, text)
        return JSONObject(text.ifEmpty { "{}" })
    } finally {
        c.disconnect()
    }
}

fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

fun msToken(vararg p: Pair<String, String>) =
    http("$MS_LOGIN/token", form("client_id" to MS_CLIENT_ID, *p), "application/x-www-form-urlencoded")

fun accessToken(ctx: Context): String {
    val p = ctx.prefs()
    val refresh = p.getString("refresh_token", null)
        ?: throw IOException("Not signed in to Microsoft. Open Settings to sign in.")
    val r = try {
        msToken("grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to MS_SCOPE)
    } catch (e: HttpError) {
        // invalid_grant also covers a sign-in made before a scope was added (no consent for Notes yet)
        if ("invalid_grant" in e.body || "interaction_required" in e.body) {
            // drop the dead token so Settings stops saying "Signed in"; skip if a new sign-in already replaced it
            if (p.getString("refresh_token", null) == refresh) p.edit().remove("refresh_token").apply()
            throw IOException("Microsoft sign-in expired. Open Settings to sign in again.")
        }
        throw e
    }
    p.edit().putString("refresh_token", r.optString("refresh_token", refresh)).apply()
    return r.getString("access_token")
}

/** The id of the built-in "Tasks" list. Graph pages long results, so follow nextLink as canvas-todo-sync does. */
fun defaultListId(get: (String) -> JSONObject): String {
    var url: String? = TODO_LISTS
    while (url != null) {
        val page = get(url)
        val lists = page.getJSONArray("value")
        (0 until lists.length()).map { lists.getJSONObject(it) }
            .firstOrNull { it.optString("wellknownListName") == "defaultList" }?.let { return it.getString("id") }
        url = page.optString("@odata.nextLink").ifEmpty { null }
    }
    throw IOException("Couldn't find your default To Do list (Tasks).")
}

// The owner's accent and the languages they mix, given to every transcription.
private const val SPEECH_RULES = "The speaker has an Asian accent and may mix English, Tagalog and Spanish in one sentence. " +
    "Write every word in the language it was spoken; never translate. Where the accent makes a word ambiguous, choose the most likely word in context."

// shortcut: Gemini takes about 20MB inline; past ~6 minutes of speech (long silences are trimmed out) the job sends the
// on-screen text instead. Use the Files API if longer notes matter.
const val MAX_AUDIO = 12_000_000L

/** The transcript Gemini returned with an audio split, or null when the split had none (typed only, or a failed call). */
fun transcriptOf(json: String?): String? =
    runCatching { JSONObject(json!!).optString("transcript").trim() }.getOrNull()?.ifEmpty { null }

private fun audioPart(pcm: ByteArray) = JSONObject().put(
    "inlineData", JSONObject().put("mimeType", "audio/wav").put("data", java.util.Base64.getEncoder().encodeToString(wav(pcm)))
)

private fun gemini(key: String, parts: JSONArray, config: JSONObject): String {
    val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", config)
    val r = http(
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
        req.toString(), headers = mapOf("x-goog-api-key" to key),
        readTimeout = 180_000, // transcribing minutes of audio can outlast the usual minute
    )
    val out = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
    return (0 until out.length()).map { out.getJSONObject(it) }
        .first { it.has("text") && !it.optBoolean("thought") }.getString("text")
}

/** One finished sentence's audio to text, for the live correction on the capture screen. */
fun geminiTranscribe(pcm: ByteArray, key: String): String = gemini(
    key,
    JSONArray().put(audioPart(pcm)).put(JSONObject().put("text",
        "Transcribe this audio. $SPEECH_RULES Return only the words spoken, with normal punctuation, and nothing else. If there is no speech, return nothing.")),
    JSONObject().put("responseMimeType", "text/plain"),
).trim()

/**
 * Sorts a capture into to-dos and a note. With [pcm] Gemini transcribes the recording first (returned as
 * "transcript") and [text] holds only words typed alongside it; without, [text] is the whole capture.
 */
fun geminiSplit(text: String, at: Long, key: String, pcm: ByteArray? = null): String {
    val now = SimpleDateFormat("EEEE, yyyy-MM-dd, h:mm a", Locale.US).format(Date(at))
    val source = if (pcm == null) "Voice note: $text" else
        "First write the recording word for word into transcript. $SPEECH_RULES Then sort the transcript" +
            (if (text.isBlank()) "." else " together with these typed words: $text")
    val prompt = """
        It is $now. Sort this voice note into to-do tasks and a note.
        - tasks: things the speaker needs to do, one per separate action. title: short, starts with a verb, keeps their own words and names. due: YYYY-MM-DD, only when they gave or implied a date ("by Friday", "tomorrow"); otherwise leave it out.
          remind: YYYY-MM-DDTHH:MM, only when they asked to be reminded or named a time of day ("at 4", "tonight at 7", "in two hours"): the next such time after now. important: true only when they called it urgent or important.
          steps: when some of what they said is only preparation for one bigger task (setting up, checking things first), make that one task and list those as its steps, in order and in their own words. Never add a step they didn't say.
        - later: things they might do or want to look into but didn't commit to ("maybe I should", "I wonder if", "what if"), one short line each in their own words. These are not tasks.
        - note: everything else worth keeping, like details, ideas, explanations and thinking out loud. title: a few words naming the topic. points: that content as short, clear bullet points in a sensible order, keeping every detail and the speaker's wording. Add no facts they didn't say.
        - If it is all to-dos, leave out the note. If it is all rambling, return an empty tasks list.
        - Keep every word in the language it was said.
        $source
    """.trimIndent()
    val schema = JSONObject(
        """{"type":"OBJECT","properties":{""" +
            """"transcript":{"type":"STRING"},""" +
            """"tasks":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"due":{"type":"STRING"},"remind":{"type":"STRING"},"important":{"type":"BOOLEAN"},"steps":{"type":"ARRAY","items":{"type":"STRING"}}},"required":["title"]}},""" +
            """"later":{"type":"ARRAY","items":{"type":"STRING"}},""" +
            """"note":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"points":{"type":"ARRAY","items":{"type":"STRING"}}},"required":["title","points"]}""" +
            """},"required":["tasks"]}"""
    )
    val parts = JSONArray().apply { if (pcm != null) put(audioPart(pcm)) }.put(JSONObject().put("text", prompt))
    return gemini(key, parts, JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
}

sealed interface Outcome {
    /** Keep the recording and ask Gemini again on the job's next try. */
    data object Retry : Outcome
    /** Only noise was recorded and nothing was typed: nothing to file. */
    data object Drop : Outcome
    /** File it, with [raw] as "what you said" in task notes and the OneNote page. */
    data class Save(val raw: String) : Outcome
}

const val MAX_TRIES = 5

/**
 * What the job does once Gemini has answered. [split] is Gemini's JSON, or "" when the call failed or there's no key.
 * A recording's own transcript is the accurate copy, so a failed call is retried, a few times when the screen showed
 * words to fall back on and indefinitely when it showed none (nothing else holds them). Gemini's transcript is used
 * only when audio was sent: for typed text it would be a rewrite of the user's exact words.
 */
fun outcome(hasAudio: Boolean, split: String, shown: String, typed: String, tries: Int, hasKey: Boolean): Outcome {
    if (hasAudio && split.isBlank() && (shown.isBlank() || hasKey && tries < MAX_TRIES)) return Outcome.Retry
    val transcript = if (hasAudio) transcriptOf(split) else null
    val raw = transcript?.let { listOf(it, typed).filter(String::isNotBlank).joinToString("\n\n") } ?: shown
    return if (raw.isBlank()) Outcome.Drop else Outcome.Save(raw)
}

/** Uploads one capture. JobScheduler gives us the offline queue, retries with backoff, and reboot survival. */
class SaveJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val ok = try {
                save(params)
                true
            } catch (e: Exception) {
                prefs().edit().putString("last_error", e.message ?: e.toString()).apply()
                false
            }
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    private fun save(params: JobParameters) {
        val shown = params.extras.getString("text")!! // what the screen showed: the fallback if Gemini can't help
        val typed = params.extras.getString("typed").orEmpty()
        val audio = params.extras.getString("audio")?.let(::File)?.takeIf { it.isFile && it.length() in 1..MAX_AUDIO }
        val at = params.extras.getLong("at")
        val p = prefs()
        val k = "job${params.jobId}"
        val cached = p.getString("${k}_split", null)
        val split = cached ?: split(if (audio == null) shown else typed, at, audio)
        val tries = p.getInt("${k}_tries", 0)
        val hasKey = !p.getString("gemini_key", null).isNullOrBlank()
        val raw = when (val o = outcome(audio != null, split, shown, typed, tries, hasKey)) {
            Outcome.Retry -> {
                p.edit().putInt("${k}_tries", tries + 1).apply()
                throw IOException("Couldn't transcribe a recording yet: ${p.getString("gemini_error", null) ?: "no Gemini key"}")
            }
            Outcome.Drop -> {
                p.edit().remove("${k}_split").remove("${k}_done").remove("${k}_tries").apply()
                params.extras.getString("audio")?.let { File(it).delete() }
                return
            }
            is Outcome.Save -> o.raw
        }
        // cache the split so a retry after a partial upload doesn't ask Gemini again and get a different split
        if (cached == null) p.edit().putString("${k}_split", split).commit()
        val parsed = parseSplit(split, raw)
        val (tasks, note) = parsed
        val token = accessToken(this)
        val auth = mapOf("Authorization" to "Bearer $token")
        val listId by lazy { defaultListId { http(it, headers = auth) } } // a note-only capture never needs it
        // Graph wants Windows zone names ("Pacific Standard Time"), same as canvas-todo-sync sends
        val tz = android.icu.util.TimeZone.getWindowsID(java.util.TimeZone.getDefault().id) ?: "UTC"
        // the done counter lets a retry resume after the last upload that landed
        val todo = uploads(parsed)
        for (u in p.getInt("${k}_done", 0) until todo.size) {
            val e = p.edit().putInt("${k}_done", u + 1)
            when (val up = todo[u]) {
                // the new task's id is saved with the counter, so its steps find it even after a retry
                is Upload.ToDo -> e.putString("${k}_task${up.task}",
                    http("$TODO_LISTS/$listId/tasks", taskJson(tasks[up.task], raw, tz).toString(), headers = auth).getString("id"))
                is Upload.Step -> http("$TODO_LISTS/$listId/tasks/${p.getString("${k}_task${up.task}", null)}/checklistItems",
                    JSONObject().put("displayName", tasks[up.task].steps[up.step]).toString(), headers = auth)
                Upload.Page -> {
                    val page = http(ONENOTE_PAGES, noteHtml(note!!, raw, at), "text/html", auth)
                    stick(stickiesFor(k, note, at, page.optJSONObject("links")))
                }
            }
            // shortcut: a POST that lands but whose reply is lost gets sent again on retry (rare duplicate, never a lost capture)
            e.commit()
        }
        val done = p.edit().remove("${k}_split").remove("${k}_done").remove("${k}_tries").remove("last_error")
        tasks.indices.forEach { done.remove("${k}_task$it") }
        done.putString("last_filed", "${tasks.size} ${if (note == null) 0 else 1}").apply() // read by the code strip (Codes.kt)
        params.extras.getString("audio")?.let { File(it).delete() } // filed: the recording has done its job
    }

    /** Gemini's JSON, or "" (meaning save unsplit) when there's no key or the call fails. */
    private fun split(text: String, at: Long, audio: File?): String {
        val key = prefs().getString("gemini_key", null)
        if (key.isNullOrBlank()) return ""
        return try {
            geminiSplit(text, at, key, audio?.readBytes()).also { prefs().edit().remove("gemini_error").apply() }
        } catch (e: Exception) {
            prefs().edit().putString("gemini_error", e.message ?: e.toString()).apply()
            ""
        }
    }

    companion object {
        /** [text] is everything the screen showed; [audio] the recording, if any; [typed] the words typed beside it. */
        fun enqueue(ctx: Context, text: String, audio: File? = null, typed: String = ""): Boolean {
            val p = ctx.prefs()
            val id = p.getInt("next_job", 1)
            p.edit().putInt("next_job", id + 1).commit()
            val job = JobInfo.Builder(id, ComponentName(ctx, SaveJob::class.java))
                // expedited: runs right away, and a warm phone (thermal "moderate", common while charging) holds back
                // normal jobs but not these; past Android's expedited quota it quietly becomes a normal job
                .setExpedited(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply {
                    putString("text", text)
                    putString("typed", typed)
                    audio?.let { putString("audio", it.path) }
                    putLong("at", System.currentTimeMillis()) // "tomorrow" means the day it was said, not the day it uploads
                })
                .build()
            // past Android's cap on waiting jobs (150), schedule() throws; report failure so the caller copies the text to the clipboard
            return runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS }
                .onFailure { Log.e("Capture", "couldn't schedule upload", it) }
                .getOrDefault(false)
        }

        /** Re-scheduling a job resets its backoff, so captures stuck behind a missing sign-in go out now. */
        fun retryAll(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.allPendingJobs.forEach { js.schedule(it) }
        }
    }
}
