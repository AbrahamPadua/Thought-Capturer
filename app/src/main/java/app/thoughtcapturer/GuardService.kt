package app.thoughtcapturer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

// A longer gap between events means the screen was locked (lock screen events are skipped) or the phone sat unused.
// shortcut: a Reel watched untouched for over 10 s counts as 10 s; if totals run low, flush on screen-off instead.
private const val MAX_TICK_SEC = 10

/**
 * Accessibility service detecting short-form feeds (Reels, Shorts, etc.) during focus sessions.
 * Debounces content updates to conserve battery, extracts visible nodes, and evaluates guard rules.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private var lastInterruptMs = 0L
    private var lastGeminiCheckMs = 0L
    internal var consecVideos = 0
    internal var wasInShortForm = false
    internal var lastShortFormTickMs = 0L
    internal var lastScrollCountMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()

        if (!isGuardedPackage(pkg)) {
            if (shouldResetOnNonGuardedEvent(pkg, event.eventType)) {
                pendingCheck?.let { handler.removeCallbacks(it) }
                pendingCheck = null
                flushShortFormTime()
                wasInShortForm = false
                consecVideos = 0
                lastScrollCountMs = 0L
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                pendingCheck?.let { handler.removeCallbacks(it) }
                val check = Runnable { processScreen(pkg, event.eventType) }
                pendingCheck = check
                handler.postDelayed(check, 400L)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                pendingCheck?.let { handler.removeCallbacks(it) }
                pendingCheck = null
                processScreen(pkg, event.eventType)
            }
        }
    }

    private fun processScreen(pkg: String, eventType: Int) {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val nodes = walkNodes(root, limit = 150)
        val kind = classifyScreen(pkg, nodes)
        val isShort = kind is ScreenKind.ShortForm
        val nowMs = System.currentTimeMillis()

        val enteringShortForm = trackShortForm(isShort, eventType, nowMs)

        val p = prefs()
        val cfg = guardConfig()
        val stats = guardStats()
        val focus = currentFocusBlock(this, nowMs)
        val passUntilMs = p.getLong("guard_pass_until", 0L)
        val passIntent = p.getString("guard_pass_intent", "").orEmpty()
        val leisureUntilMs = p.getLong("guard_leisure_until", 0L)

        if (cfg.stage == EvalStage.BASELINE && enteringShortForm) {
            updateGuardStats { it.copy(unplannedOpenings = it.unplannedOpenings + 1) }
        }

        val action = evaluateGuard(
            config = cfg,
            kind = kind,
            nodes = nodes,
            pkg = pkg,
            focus = focus,
            stats = stats,
            nowMs = nowMs,
            passUntilMs = passUntilMs,
            passIntent = passIntent,
            leisureUntilMs = leisureUntilMs,
            consecVideos = consecVideos,
        )

        when (action) {
            is GuardAction.Allow -> { /* allow */ }
            is GuardAction.CheckGemini -> {
                if (shouldCheckGemini(nowMs)) {
                    val key = prefs().getString("gemini_key", "").orEmpty()
                    val leisureRemainingMin = ((cfg.leisureBudgetMin * 60 - stats.shortFormSec).coerceAtLeast(0)) / 60
                    thread {
                        val matches = verifyPurposeGemini(action.intent, action.headers, key)
                        if (!matches) {
                            handler.post {
                                // the answer can take 15 s: act only if that app is still in front, or Back lands in whatever you opened since
                                if (runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() != pkg) return@post
                                prefs().edit().remove("guard_pass_until").apply()
                                updateGuardStats { it.copy(unplannedOpenings = it.unplannedOpenings + 1) }
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                val feedName = if (kind is ScreenKind.ShortForm) kind.feedName else pkg
                                launchGuardActivity(
                                    feed = feedName,
                                    reason = "Screen titles did not match your stated purpose (${action.intent}).",
                                    focusTitle = focus?.title,
                                    nextAction = focus?.nextAction,
                                    allowPurposePass = cfg.stage >= EvalStage.GUARD_RETURN,
                                    allowLeisureBreak = focus == null && leisureRemainingMin > 0,
                                    leisureRemainingMin = leisureRemainingMin,
                                    triggerCountdown = false,
                                    sendSupportiveNotice = false,
                                )
                            }
                        }
                    }
                }
            }
            is GuardAction.Interrupt -> {
                if (nowMs - lastInterruptMs < 1500L) return
                lastInterruptMs = nowMs
                updateGuardStats { it.copy(unplannedOpenings = it.unplannedOpenings + 1) }
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchGuardActivity(
                    feed = action.feedOrApp,
                    reason = action.reason,
                    focusTitle = action.focus?.title,
                    nextAction = action.focus?.nextAction,
                    allowPurposePass = action.allowPurposePass,
                    allowLeisureBreak = action.allowLeisureBreak,
                    leisureRemainingMin = action.leisureRemainingMin,
                    triggerCountdown = action.triggerCountdown,
                    sendSupportiveNotice = action.sendSupportiveNotice,
                )
            }
        }
    }

    private fun walkNodes(root: AccessibilityNodeInfo, limit: Int): List<ScreenNode> {
        val list = mutableListOf<ScreenNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && list.size < limit) {
            val node = queue.removeFirst()
            list.add(ScreenNode(
                viewId = node.viewIdResourceName,
                text = node.text?.toString(),
                desc = node.contentDescription?.toString(),
                selected = node.isSelected,
                editable = node.isEditable,
                password = node.isPassword,
            ))
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) queue.add(child)
            }
        }
        return list
    }

    // one check per 20 s whatever the screen says: every swipe changes its text, so a per-question limit meant a call per scroll
    internal fun shouldCheckGemini(nowMs: Long): Boolean {
        if (lastGeminiCheckMs > 0L && nowMs - lastGeminiCheckMs < 20_000L) return false
        lastGeminiCheckMs = nowMs
        return true
    }

    internal fun shouldResetOnNonGuardedEvent(pkg: String, eventType: Int): Boolean {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        val p = pkg.lowercase()
        return p.isNotEmpty() && p != "com.android.systemui" && !p.contains("inputmethod") && !p.contains("honeyboard")
    }

    internal fun trackShortForm(isShort: Boolean, eventType: Int, nowMs: Long): Boolean {
        if (isShort) {
            if (!wasInShortForm) {
                lastShortFormTickMs = nowMs
            } else {
                val elapsedSec = ((nowMs - lastShortFormTickMs) / 1000).toInt()
                if (elapsedSec >= 1) {
                    updateGuardStats { it.copy(shortFormSec = it.shortFormSec + minOf(elapsedSec, MAX_TICK_SEC)) }
                    lastShortFormTickMs = nowMs
                }
            }
            if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && nowMs - lastScrollCountMs >= 1500L) {
                consecVideos++
                lastScrollCountMs = nowMs
            }
        } else {
            flushShortFormTime(nowMs)
            consecVideos = 0
            lastScrollCountMs = 0L
        }
        val enteringShortForm = isShort && !wasInShortForm
        wasInShortForm = isShort
        return enteringShortForm
    }

    internal fun flushShortFormTime(now: Long = System.currentTimeMillis()) {
        if (wasInShortForm && lastShortFormTickMs > 0L) {
            val elapsedSec = ((now - lastShortFormTickMs) / 1000).toInt()
            if (elapsedSec > 0) {
                updateGuardStats { it.copy(shortFormSec = it.shortFormSec + minOf(elapsedSec, MAX_TICK_SEC)) }
                lastShortFormTickMs = now
            }
        }
    }

    private fun launchGuardActivity(
        feed: String,
        reason: String,
        focusTitle: String?,
        nextAction: String?,
        allowPurposePass: Boolean,
        allowLeisureBreak: Boolean,
        leisureRemainingMin: Int,
        triggerCountdown: Boolean,
        sendSupportiveNotice: Boolean,
    ) {
        val intent = Intent(this, GuardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("feed", feed)
            putExtra("reason", reason)
            putExtra("focusTitle", focusTitle)
            putExtra("nextAction", nextAction)
            putExtra("allowPurposePass", allowPurposePass)
            putExtra("allowLeisureBreak", allowLeisureBreak)
            putExtra("leisureRemainingMin", leisureRemainingMin)
            putExtra("triggerCountdown", triggerCountdown)
            putExtra("sendSupportiveNotice", sendSupportiveNotice)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        flushShortFormTime()
        pendingCheck?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        flushShortFormTime()
        pendingCheck?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
