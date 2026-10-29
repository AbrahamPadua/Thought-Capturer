package app.thoughtcapturer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
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

data class Task(val title: String, val due: String?)
data class Note(val title: String, val points: List<String>)
data class Split(val tasks: List<Task>, val note: Note?)

/** Gemini's JSON to tasks and an optional note. Anything unusable falls back to the raw capture as one task. */
fun parseSplit(json: String?, raw: String): Split {
    val parsed = runCatching {
        val o = JSONObject(json ?: "")
        val a = o.optJSONArray("tasks") ?: JSONArray()
        val tasks = (0 until a.length()).map { a.getJSONObject(it) }
            // a real date only: To Do rejects "2027-02-29", and that would fail the upload forever
            .map { Task(it.optString("title").trim(), it.optString("due").takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess }) }
            .filter { it.title.isNotEmpty() }
        val n = o.optJSONObject("note")
        val points = n?.optJSONArray("points")?.let { p -> (0 until p.length()).map { p.optString(it).trim() } }.orEmpty().filter { it.isNotEmpty() }
        Split(tasks, n?.takeIf { points.isNotEmpty() }?.let { Note(it.optString("title").trim().ifEmpty { "Capture" }, points) })
    }.getOrNull()
    val split = parsed?.takeIf { it.tasks.isNotEmpty() || it.note != null } ?: Split(listOf(Task(raw, null)), null)
    // To Do caps titles at 255 chars; the full capture also goes in the task note
    return split.copy(tasks = split.tasks.map { it.copy(title = it.title.take(255)) })
}

/** The OneNote page. Everything is escaped: a stray "<" or "&" in speech would get the page rejected and stall the upload. */
fun noteHtml(note: Note, raw: String, at: Long): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(at)) // the time you said it, not the upload time
    return "<!DOCTYPE html><html><head><title>${esc(note.title)}</title><meta name=\"created\" content=\"$created\" /></head><body>" +
        "<ul>${note.points.joinToString("") { "<li>${esc(it)}</li>" }}</ul><p><i>What you said:</i> ${esc(raw)}</p></body></html>"
}

class HttpError(val code: Int, val body: String) : IOException("HTTP $code ${body.take(300)}")

fun http(url: String, body: String? = null, type: String = "application/json", headers: Map<String, String> = emptyMap()): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 60_000
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
        ?: throw IOException("Not signed in to Microsoft. Open settings (⚙) to sign in.")
    val r = try {
        msToken("grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to MS_SCOPE)
    } catch (e: HttpError) {
        // invalid_grant also covers a sign-in made before a scope was added (no consent for Notes yet)
        if ("invalid_grant" in e.body || "interaction_required" in e.body) {
            // drop the dead token so Settings stops saying "Signed in"; skip if a new sign-in already replaced it
            if (p.getString("refresh_token", null) == refresh) p.edit().remove("refresh_token").apply()
            throw IOException("Microsoft sign-in expired. Open settings (⚙) to sign in again.")
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

fun geminiSplit(text: String, at: Long, key: String): String {
    val now = SimpleDateFormat("EEEE, yyyy-MM-dd, h:mm a", Locale.US).format(Date(at))
    val prompt = """
        It is $now. Sort this voice note into to-do tasks and a note.
        - tasks: things the speaker needs to do, one per separate action. title: short, starts with a verb, keeps their own words and names. due: YYYY-MM-DD, only when they gave or implied a date ("by Friday", "tomorrow"); otherwise leave it out.
        - note: everything else worth keeping, like details, ideas, explanations and thinking out loud. title: a few words naming the topic. points: that content as short, clear bullet points in a sensible order, keeping every detail and the speaker's wording. Add no facts they didn't say.
        - If it is all to-dos, leave out the note. If it is all rambling, return an empty tasks list.
        Voice note: $text
    """.trimIndent()
    val schema = JSONObject(
        """{"type":"OBJECT","properties":{""" +
            """"tasks":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"due":{"type":"STRING"}},"required":["title"]}},""" +
            """"note":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"points":{"type":"ARRAY","items":{"type":"STRING"}}},"required":["title","points"]}""" +
            """},"required":["tasks"]}"""
    )
    val req = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
    val r = http(
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
        req.toString(), headers = mapOf("x-goog-api-key" to key)
    )
    val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
    return (0 until parts.length()).map { parts.getJSONObject(it) }
        .first { it.has("text") && !it.optBoolean("thought") }.getString("text")
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
        val raw = params.extras.getString("text")!!
        val at = params.extras.getLong("at")
        val p = prefs()
        val k = "job${params.jobId}"
        // cache the split so a retry after a partial upload doesn't ask Gemini again and get a different split
        val split = p.getString("${k}_split", null)
            ?: split(raw, at).also { p.edit().putString("${k}_split", it).commit() }
        val (tasks, note) = parseSplit(split, raw)
        val token = accessToken(this)
        val auth = mapOf("Authorization" to "Bearer $token")
        val listId by lazy { defaultListId { http(it, headers = auth) } } // a note-only capture never needs it
        // Graph wants Windows zone names ("Pacific Standard Time"), same as canvas-todo-sync sends
        val tz = android.icu.util.TimeZone.getWindowsID(java.util.TimeZone.getDefault().id) ?: "UTC"
        // tasks first, then the note; the done counter lets a retry resume after the last upload that landed
        for (i in p.getInt("${k}_done", 0) until tasks.size + (if (note == null) 0 else 1)) {
            if (i < tasks.size) {
                val t = tasks[i]
                val task = JSONObject().put("title", t.title)
                if (t.title != raw) task.put("body", JSONObject().put("content", raw).put("contentType", "text"))
                if (t.due != null) task.put("dueDateTime", JSONObject().put("dateTime", "${t.due}T00:00:00").put("timeZone", tz))
                http("$TODO_LISTS/$listId/tasks", task.toString(), headers = auth)
            } else {
                http(ONENOTE_PAGES, noteHtml(note!!, raw, at), "text/html", auth)
            }
            // shortcut: a POST that lands but whose reply is lost gets sent again on retry (rare duplicate, never a lost capture)
            p.edit().putInt("${k}_done", i + 1).commit()
        }
        p.edit().remove("${k}_split").remove("${k}_done").remove("last_error").apply()
    }

    /** Gemini's JSON, or "" (meaning save unsplit) when there's no key or the call fails. */
    private fun split(raw: String, at: Long): String {
        val key = prefs().getString("gemini_key", null)
        if (key.isNullOrBlank()) return ""
        return try {
            geminiSplit(raw, at, key).also { prefs().edit().remove("gemini_error").apply() }
        } catch (e: Exception) {
            prefs().edit().putString("gemini_error", e.message ?: e.toString()).apply()
            ""
        }
    }

    companion object {
        fun enqueue(ctx: Context, text: String): Boolean {
            val p = ctx.prefs()
            val id = p.getInt("next_job", 1)
            p.edit().putInt("next_job", id + 1).commit()
            val job = JobInfo.Builder(id, ComponentName(ctx, SaveJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply {
                    putString("text", text)
                    putLong("at", System.currentTimeMillis()) // "tomorrow" means the day it was said, not the day it uploads
                })
                .build()
            // past Android's cap on waiting jobs (150), schedule() throws; report failure so the caller copies the text to the clipboard
            return runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS }
                .getOrDefault(false)
        }

        /** Re-scheduling a job resets its backoff, so captures stuck behind a missing sign-in go out now. */
        fun retryAll(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.allPendingJobs.forEach { js.schedule(it) }
        }
    }
}
