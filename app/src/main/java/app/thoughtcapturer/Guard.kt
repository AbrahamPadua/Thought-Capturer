package app.thoughtcapturer

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Settings
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

enum class EvalStage(val label: String) {
    BASELINE("1 · Baseline (observe only)"),
    GUARD_ONLY("2 · Guard only (block feeds)"),
    GUARD_RETURN("3 · Guard + Return to task"),
    GUARD_ACCOUNTABILITY("4 · Guard + Return + Accountability"),
}

enum class AccountabilityMode(val label: String) {
    OFF("Off"),
    SUPPORTIVE("Supportive check-in"),
    CONSEQUENCE("30s countdown + notice"),
}

data class ScreenNode(
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val selected: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
)

sealed interface ScreenKind {
    data class ShortForm(val feedName: String) : ScreenKind
    data class SafeSection(val sectionName: String) : ScreenKind
    data class GuardedApp(val appName: String) : ScreenKind
    data object Other : ScreenKind
}

sealed interface PurposeMatch {
    data object Match : PurposeMatch
    data class Uncertain(val headers: List<String>) : PurposeMatch
    data class Mismatch(val reason: String) : PurposeMatch
}

data class FocusBlock(val title: String, val nextAction: String? = null, val endMs: Long = 0L)

data class GuardConfig(
    val stage: EvalStage = EvalStage.GUARD_RETURN,
    val accountabilityMode: AccountabilityMode = AccountabilityMode.OFF,
    val leisureBudgetMin: Int = 15,
    val videoLimit: Int = 5,
    val violationThreshold: Int = 2,
    val focusTask: String = "",
    val partnerTarget: String = "",
    val useGeminiVerify: Boolean = true,
)

data class GuardStats(
    val day: String,
    val shortFormSec: Int = 0,
    val unplannedOpenings: Int = 0,
    val returnedToTask: Int = 0,
    val overrides: Int = 0,
    val notificationsSent: Int = 0,
)

sealed interface GuardAction {
    data object Allow : GuardAction
    data class CheckGemini(val intent: String, val headers: List<String>) : GuardAction
    data class Interrupt(
        val feedOrApp: String,
        val reason: String,
        val focus: FocusBlock?,
        val allowPurposePass: Boolean,
        val allowLeisureBreak: Boolean,
        val leisureRemainingMin: Int,
        val triggerCountdown: Boolean,
        val sendSupportiveNotice: Boolean,
    ) : GuardAction
}

val GUARDED_PACKAGES = setOf(
    "com.instagram.android",
    "com.google.android.youtube",
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
)

fun isGuardedPackage(pkg: String): Boolean = pkg.trim().lowercase() in GUARDED_PACKAGES

/** Stage 1 local deterministic classifier identifying short-form feeds vs safe sections. */
fun classifyScreen(pkg: String, nodes: List<ScreenNode>): ScreenKind {
    return when (pkg.trim().lowercase()) {
        "com.instagram.android" -> {
            val inDm = nodes.any { n ->
                val v = n.viewId.orEmpty().lowercase()
                v.contains("direct_thread") ||
                    v.contains("row_inbox") ||
                    v.contains("thread_title") ||
                    v.contains("direct_inbox") ||
                    v.contains("message_list") ||
                    (n.selected && (
                        n.desc.equals("Direct", ignoreCase = true) ||
                            n.text.equals("Direct", ignoreCase = true) ||
                            n.desc.equals("Messages", ignoreCase = true) ||
                            n.text.equals("Messages", ignoreCase = true)
                    ))
            }
            val inReels = nodes.any { n ->
                val v = n.viewId.orEmpty().lowercase()
                v.contains("clips_viewer_view_pager") ||
                    v.contains("reel_viewer_root") ||
                    v.contains("clips_video_container") ||
                    v.contains("clips_viewer_fragment") ||
                    (v.contains("clips_tab") && n.selected) ||
                    (!inDm && n.selected && (
                        n.desc.equals("Reels", ignoreCase = true) ||
                            n.text.equals("Reels", ignoreCase = true)
                    ))
            }
            when {
                inReels -> ScreenKind.ShortForm("Instagram Reels")
                inDm -> ScreenKind.SafeSection("Instagram DMs")
                else -> ScreenKind.GuardedApp("Instagram")
            }
        }
        "com.google.android.youtube" -> {
            val inShorts = nodes.any { n ->
                val v = n.viewId.orEmpty().lowercase()
                v.contains("reel_recycler") ||
                    v.contains("reel_player_page_container") ||
                    v.contains("shorts_player") ||
                    v.contains("reel_watch_fragment") ||
                    (n.selected && (
                        n.text.equals("Shorts", ignoreCase = true) ||
                            n.desc.equals("Shorts", ignoreCase = true)
                    ))
            }
            if (inShorts) return ScreenKind.ShortForm("YouTube Shorts")
            val inSafeVideo = nodes.any { n ->
                val v = n.viewId.orEmpty().lowercase()
                v.contains("watch_while") ||
                    v.contains("player_view") ||
                    v.contains("video_title") ||
                    v.contains("results") ||
                    v.contains("search")
            }
            if (inSafeVideo) return ScreenKind.SafeSection("YouTube Video")
            ScreenKind.GuardedApp("YouTube")
        }
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> ScreenKind.ShortForm("TikTok")
        else -> ScreenKind.Other
    }
}

private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
private val PHONE_REGEX = Regex("""(?:\+?\d{1,3}[-.\s]?)?(?:\(\d{3}\)|\b\d{3})[-.\s]?\d{3}[-.\s]?\d{4}\b""")
private val SENSITIVE_IDS = listOf("message_text", "chat", "input", "composer", "comment")

/** Collects visible section titles, redacting PII and filtering out editable/chat inputs. */
fun extractRedactedHeaders(nodes: List<ScreenNode>, maxLines: Int = 10): List<String> {
    val results = mutableListOf<String>()
    for (node in nodes) {
        if (node.editable || node.password) continue
        val v = node.viewId.orEmpty().lowercase()
        if (SENSITIVE_IDS.any { v.contains(it) }) continue
        for (raw in listOfNotNull(node.text, node.desc)) {
            val trimmed = raw.trim()
            if (trimmed.length in 3..120) {
                val redacted = trimmed
                    .replace(EMAIL_REGEX, "[redacted]")
                    .replace(PHONE_REGEX, "[redacted]")
                    .trim()
                if (redacted.isNotBlank()) {
                    results.add(redacted)
                }
            }
        }
    }
    return results.distinct().take(maxLines)
}

private val STOPWORDS = setOf(
    "the", "and", "for", "with", "watching", "need",
    "check", "open", "about", "from", "into", "that", "this"
)
private val MSG_KEYWORDS = setOf("dm", "dms", "message", "messages", "reply", "replying", "text", "chat")

/** Stage 2 local semantic check verifying if screen titles match stated purpose. */
fun verifyPurposeLocal(
    intent: String,
    pkg: String,
    kind: ScreenKind,
    nodes: List<ScreenNode>,
): PurposeMatch {
    if (kind is ScreenKind.ShortForm) {
        return PurposeMatch.Mismatch("${kind.feedName} is blocked during an important-use pass.")
    }
    val headers = extractRedactedHeaders(nodes)
    val tokens = Regex("[a-zA-Z0-9]+").findAll(intent.lowercase()).map { it.value }.toList()
    val keywords = tokens.filter { it.length >= 3 && it !in STOPWORDS }

    if (kind == ScreenKind.SafeSection("Instagram DMs")) {
        if (tokens.any { it in MSG_KEYWORDS }) return PurposeMatch.Match
    }

    if (headers.isEmpty()) return PurposeMatch.Match

    val headersText = headers.joinToString(" ").lowercase()
    if (keywords.any { it in headersText }) return PurposeMatch.Match

    return PurposeMatch.Uncertain(headers)
}

/** Stage 3 Gemini API call to verify ambiguous headers against intent. */
fun geminiIntentCall(intent: String, headers: List<String>, key: String): String {
    val prompt = "Stated purpose: $intent\nVisible screen titles: ${headers.joinToString(" | ")}\nCould this screen plausibly relate to the stated purpose (including searching, navigating, or viewing related material)? Return JSON {\"matches\": true} unless it is clearly unrelated entertainment."
    val schema = JSONObject("""{"type":"OBJECT","properties":{"matches":{"type":"BOOLEAN"}},"required":["matches"]}""")
    val config = JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
    val parts = JSONArray().put(JSONObject().put("text", prompt))
    val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", config)
    val r = http(
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
        req.toString(),
        headers = mapOf("x-goog-api-key" to key),
        readTimeout = 15_000,
    )
    val out = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
    return (0 until out.length()).map { out.getJSONObject(it) }
        .first { it.has("text") && !it.optBoolean("thought") }.getString("text")
}

/** Fail-open Gemini semantic verification wrapper. */
fun verifyPurposeGemini(
    intent: String,
    headers: List<String>,
    key: String,
    ask: (String, List<String>, String) -> String = ::geminiIntentCall,
): Boolean {
    if (key.isBlank() || headers.isEmpty()) return true
    return runCatching {
        val reply = ask(intent, headers, key)
        JSONObject(reply).optBoolean("matches", true)
    }.getOrDefault(true)
}

private val FOCUS_WORDS = setOf(
    "focus", "study", "research", "homework", "assignment", "exam",
    "lecture", "lab", "paper", "thesis", "project", "coursework"
)
private val COURSE_CODE_REGEX = Regex("""\b[A-Za-z]{2,5}\s*\d{1,3}[A-Za-z]?\b""")

fun isFocusEventTitle(title: String, desc: String = ""): Boolean {
    val combined = "$title $desc".lowercase()
    if (FOCUS_WORDS.any { it in combined }) return true
    return COURSE_CODE_REGEX.containsMatchIn("$title $desc")
}

fun selectFocusBlock(
    calendarEvents: List<FocusBlock>,
    manualFocusTask: String,
    fallbackTasks: List<String> = emptyList(),
): FocusBlock? {
    if (calendarEvents.isNotEmpty()) {
        val first = calendarEvents.first()
        val next = first.nextAction ?: manualFocusTask.ifBlank { fallbackTasks.firstOrNull() }
        return first.copy(nextAction = next)
    }
    if (manualFocusTask.isNotBlank()) {
        return FocusBlock(manualFocusTask.trim(), fallbackTasks.firstOrNull())
    }
    return null
}

fun queryCalendarFocusBlocks(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<FocusBlock> {
    if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    return runCatching {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, nowMs)
        ContentUris.appendId(builder, nowMs + 60_000L)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.END,
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.ALL_DAY} = 0"
        ctx.contentResolver.query(builder.build(), projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            val results = mutableListOf<FocusBlock>()
            val titleCol = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val descCol = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
            val endCol = cursor.getColumnIndex(CalendarContract.Instances.END)
            while (cursor.moveToNext()) {
                val title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                val desc = if (descCol >= 0) cursor.getString(descCol).orEmpty() else ""
                val endMs = if (endCol >= 0) cursor.getLong(endCol) else 0L
                if (isFocusEventTitle(title, desc)) {
                    results.add(FocusBlock(title = title, nextAction = null, endMs = endMs))
                }
            }
            results
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

private var cachedCalendarAtMs = 0L
private var cachedCalendarBlocks = emptyList<FocusBlock>()

fun currentFocusBlock(ctx: Context, nowMs: Long = System.currentTimeMillis()): FocusBlock? {
    if (cachedCalendarAtMs == 0L || kotlin.math.abs(nowMs - cachedCalendarAtMs) >= 60_000L) {
        cachedCalendarBlocks = queryCalendarFocusBlocks(ctx, nowMs)
        cachedCalendarAtMs = nowMs
    }
    val manual = ctx.guardConfig().focusTask
    val fallbacks = stickies(ctx.prefs().getString("sticky", null)).map { it.title }
    return selectFocusBlock(cachedCalendarBlocks, manual, fallbacks)
}

/** Core policy evaluation deciding whether to allow, verify with Gemini, or interrupt. */
fun evaluateGuard(
    config: GuardConfig,
    kind: ScreenKind,
    nodes: List<ScreenNode>,
    pkg: String,
    focus: FocusBlock?,
    stats: GuardStats,
    nowMs: Long,
    passUntilMs: Long,
    passIntent: String,
    leisureUntilMs: Long,
    consecVideos: Int,
): GuardAction {
    if (config.stage == EvalStage.BASELINE || kind is ScreenKind.Other) {
        return GuardAction.Allow
    }

    val leisureRemainingMin = ((config.leisureBudgetMin * 60 - stats.shortFormSec).coerceAtLeast(0)) / 60

    var overrideReason: String? = null
    if (nowMs < passUntilMs) {
        if (passIntent == "false positive") return GuardAction.Allow
        when (val m = verifyPurposeLocal(passIntent, pkg, kind, nodes)) {
            PurposeMatch.Match -> return GuardAction.Allow
            is PurposeMatch.Uncertain -> {
                return if (config.useGeminiVerify) GuardAction.CheckGemini(passIntent, m.headers) else GuardAction.Allow
            }
            is PurposeMatch.Mismatch -> {
                overrideReason = m.reason
            }
        }
    }

    if (kind is ScreenKind.SafeSection) return GuardAction.Allow
    if (kind is ScreenKind.GuardedApp) return GuardAction.Allow

    if (kind is ScreenKind.ShortForm) {
        val feed = kind.feedName
        if (focus != null) {
            val isAccountability = config.stage == EvalStage.GUARD_ACCOUNTABILITY
            val reachedThreshold = stats.overrides + 1 >= config.violationThreshold
            return GuardAction.Interrupt(
                feedOrApp = feed,
                reason = overrideReason ?: "Blocked during ${focus.title}.",
                focus = if (config.stage >= EvalStage.GUARD_RETURN) focus else null,
                allowPurposePass = config.stage >= EvalStage.GUARD_RETURN,
                allowLeisureBreak = false,
                leisureRemainingMin = leisureRemainingMin,
                triggerCountdown = isAccountability && config.accountabilityMode == AccountabilityMode.CONSEQUENCE && reachedThreshold,
                sendSupportiveNotice = isAccountability && config.accountabilityMode == AccountabilityMode.SUPPORTIVE && reachedThreshold,
            )
        } else {
            if (overrideReason == null && nowMs < leisureUntilMs && leisureRemainingMin > 0 && consecVideos < config.videoLimit) {
                return GuardAction.Allow
            }
            val reason = overrideReason ?: when {
                leisureRemainingMin <= 0 -> "Daily ${config.leisureBudgetMin}m short-form allowance used."
                consecVideos >= config.videoLimit -> "Reached $consecVideos consecutive short videos."
                else -> "$feed is locked by default."
            }
            return GuardAction.Interrupt(
                feedOrApp = feed,
                reason = reason,
                focus = null,
                allowPurposePass = config.stage >= EvalStage.GUARD_RETURN,
                allowLeisureBreak = leisureRemainingMin > 0,
                leisureRemainingMin = leisureRemainingMin,
                triggerCountdown = false,
                sendSupportiveNotice = false,
            )
        }
    }

    return GuardAction.Allow
}

fun shouldNotifyPartner(
    stage: EvalStage,
    mode: AccountabilityMode,
    overridesToday: Int,
    threshold: Int,
    lastNotifiedMs: Long,
    nowMs: Long,
): Boolean {
    return stage == EvalStage.GUARD_ACCOUNTABILITY &&
        mode != AccountabilityMode.OFF &&
        overridesToday >= threshold &&
        (lastNotifiedMs == 0L || nowMs - lastNotifiedMs >= 2 * 3600_000L)
}

fun accountabilityMessage(mode: AccountabilityMode, focusTitle: String?): String {
    val task = if (!focusTitle.isNullOrBlank()) " while focusing on $focusTitle" else ""
    return when (mode) {
        AccountabilityMode.SUPPORTIVE ->
            "Thought Capturer check-in: repeated short-form feed interruptions$task. A quick encouraging check-in might help."
        AccountabilityMode.CONSEQUENCE ->
            "Thought Capturer alert: repeated short-form feed overrides$task after countdown."
        AccountabilityMode.OFF -> ""
    }
}

fun sendAccountabilityNotice(ctx: Context, target: String, message: String): Boolean {
    val cleanTarget = target.trim()
    if (cleanTarget.isBlank()) return false
    if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://")) {
        return runCatching {
            val body = JSONObject().put("content", message).put("text", message).toString()
            http(cleanTarget, body = body, type = "application/json", readTimeout = 15_000)
            true
        }.getOrDefault(false)
    }
    if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    return runCatching {
        val sms = ctx.getSystemService(android.telephony.SmsManager::class.java) ?: return false
        sms.sendTextMessage(cleanTarget, null, message, null, null)
        true
    }.getOrDefault(false)
}

fun todayDay(): String = LocalDate.now().toString()

fun Context.guardConfig(): GuardConfig {
    val p = prefs()
    val stageStr = p.getString("guard_stage", null)
    val stage = runCatching { EvalStage.valueOf(stageStr!!) }.getOrDefault(EvalStage.GUARD_RETURN)
    val modeStr = p.getString("guard_accountability_mode", null)
    val mode = runCatching { AccountabilityMode.valueOf(modeStr!!) }.getOrDefault(AccountabilityMode.OFF)
    return GuardConfig(
        stage = stage,
        accountabilityMode = mode,
        leisureBudgetMin = p.getInt("guard_leisure_budget_min", 15),
        videoLimit = p.getInt("guard_video_limit", 5),
        violationThreshold = p.getInt("guard_violation_threshold", 2),
        focusTask = p.getString("guard_focus_task", "") ?: "",
        partnerTarget = p.getString("guard_partner_target", "") ?: "",
        useGeminiVerify = p.getBoolean("guard_gemini_verify", true),
    )
}

fun Context.saveGuardConfig(cfg: GuardConfig) {
    prefs().edit()
        .putString("guard_stage", cfg.stage.name)
        .putString("guard_accountability_mode", cfg.accountabilityMode.name)
        .putInt("guard_leisure_budget_min", cfg.leisureBudgetMin)
        .putInt("guard_video_limit", cfg.videoLimit)
        .putInt("guard_violation_threshold", cfg.violationThreshold)
        .putString("guard_focus_task", cfg.focusTask)
        .putString("guard_partner_target", cfg.partnerTarget)
        .putBoolean("guard_gemini_verify", cfg.useGeminiVerify)
        .apply()
}

fun Context.guardStats(day: String = todayDay()): GuardStats {
    val raw = prefs().getString("guard_stats", null) ?: return GuardStats(day)
    return runCatching {
        val o = JSONObject(raw)
        if (o.optString("day") != day) return GuardStats(day)
        GuardStats(
            day = day,
            shortFormSec = o.optInt("shortFormSec", 0),
            unplannedOpenings = o.optInt("unplannedOpenings", 0),
            returnedToTask = o.optInt("returnedToTask", 0),
            overrides = o.optInt("overrides", 0),
            notificationsSent = o.optInt("notificationsSent", 0),
        )
    }.getOrDefault(GuardStats(day))
}

fun Context.updateGuardStats(day: String = todayDay(), transform: (GuardStats) -> GuardStats): GuardStats {
    val updated = transform(guardStats(day))
    val o = JSONObject()
        .put("day", day)
        .put("shortFormSec", updated.shortFormSec)
        .put("unplannedOpenings", updated.unplannedOpenings)
        .put("returnedToTask", updated.returnedToTask)
        .put("overrides", updated.overrides)
        .put("notificationsSent", updated.notificationsSent)
    prefs().edit().putString("guard_stats", o.toString()).apply()
    return updated
}

fun GuardStats.summaryText(cfg: GuardConfig): String {
    val leisureMin = shortFormSec / 60
    val notifPart = if (notificationsSent > 0) " · $notificationsSent notices sent" else ""
    return "$leisureMin / ${cfg.leisureBudgetMin}m leisure · $unplannedOpenings blocked · $returnedToTask returned · $overrides overrides$notifPart"
}

fun Context.isGuardServiceEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.contains(packageName)
}
