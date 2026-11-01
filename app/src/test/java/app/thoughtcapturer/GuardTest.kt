package app.thoughtcapturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test
    fun classifiesInstagramScreens() {
        val reelsNode = listOf(ScreenNode(viewId = "com.instagram.android:id/clips_viewer_view_pager"))
        assertEquals(ScreenKind.ShortForm("Instagram Reels"), classifyScreen("com.instagram.android", reelsNode))

        val tabNode = listOf(ScreenNode(viewId = "clips_tab", selected = true))
        assertEquals(ScreenKind.ShortForm("Instagram Reels"), classifyScreen("com.instagram.android", tabNode))

        val reelsText = listOf(ScreenNode(text = "Reels", selected = true))
        assertEquals(ScreenKind.ShortForm("Instagram Reels"), classifyScreen("com.instagram.android", reelsText))

        val dmNode = listOf(ScreenNode(viewId = "com.instagram.android:id/direct_thread_layout"))
        assertEquals(ScreenKind.SafeSection("Instagram DMs"), classifyScreen("com.instagram.android", dmNode))

        val dmSelected = listOf(ScreenNode(desc = "Direct", selected = true))
        assertEquals(ScreenKind.SafeSection("Instagram DMs"), classifyScreen("com.instagram.android", dmSelected))

        // A reel mentioned inside a DM thread must remain classified as safe DMs
        val dmWithReel = listOf(
            ScreenNode(viewId = "thread_title"),
            ScreenNode(text = "Reels", selected = true),
        )
        assertEquals(ScreenKind.SafeSection("Instagram DMs"), classifyScreen("com.instagram.android", dmWithReel))

        val feedNode = listOf(ScreenNode(viewId = "main_feed"))
        assertEquals(ScreenKind.GuardedApp("Instagram"), classifyScreen("com.instagram.android", feedNode))
    }

    @Test
    fun classifiesYouTubeScreens() {
        val shortsRecycler = listOf(ScreenNode(viewId = "com.google.android.youtube:id/reel_recycler"))
        assertEquals(ScreenKind.ShortForm("YouTube Shorts"), classifyScreen("com.google.android.youtube", shortsRecycler))

        val shortsSelected = listOf(ScreenNode(text = "Shorts", selected = true))
        assertEquals(ScreenKind.ShortForm("YouTube Shorts"), classifyScreen("com.google.android.youtube", shortsSelected))

        val watchWhile = listOf(ScreenNode(viewId = "com.google.android.youtube:id/watch_while"))
        assertEquals(ScreenKind.SafeSection("YouTube Video"), classifyScreen("com.google.android.youtube", watchWhile))

        val videoTitle = listOf(ScreenNode(viewId = "com.google.android.youtube:id/video_title"))
        assertEquals(ScreenKind.SafeSection("YouTube Video"), classifyScreen("com.google.android.youtube", videoTitle))

        val homeFeed = listOf(ScreenNode(viewId = "home_feed"))
        assertEquals(ScreenKind.GuardedApp("YouTube"), classifyScreen("com.google.android.youtube", homeFeed))
    }

    @Test
    fun classifiesTikTokAndOtherApps() {
        assertTrue(isGuardedPackage("com.instagram.android"))
        assertTrue(isGuardedPackage("com.google.android.youtube"))
        assertTrue(isGuardedPackage("com.zhiliaoapp.musically"))
        assertFalse(isGuardedPackage("com.android.chrome"))
        assertEquals(ScreenKind.ShortForm("TikTok"), classifyScreen("com.zhiliaoapp.musically", emptyList()))
        assertEquals(ScreenKind.ShortForm("TikTok"), classifyScreen("com.ss.android.ugc.trill", emptyList()))
        assertEquals(ScreenKind.Other, classifyScreen("com.android.settings", emptyList()))
    }

    @Test
    fun extractsRedactedHeadersWithoutPrivateData() {
        val nodes = listOf(
            ScreenNode(text = "Sensitive chat body", viewId = "direct_message_text"),
            ScreenNode(text = "Comment text", viewId = "comment_input"),
            ScreenNode(text = "Secret password", password = true),
            ScreenNode(text = "Drafting text", editable = true),
            ScreenNode(text = "hi"), // Under 3 chars
            ScreenNode(text = "Email Prof. Miller at miller@ucsd.edu today"),
            ScreenNode(text = "Call lab phone at (858) 534-2230 for pickup"),
            ScreenNode(text = "Email Prof. Miller at miller@ucsd.edu today"), // Duplicate
            ScreenNode(text = "Course announcements and weekly reading schedule"),
        )
        val headers = extractRedactedHeaders(nodes, maxLines = 5)
        assertEquals(
            listOf(
                "Email Prof. Miller at [redacted] today",
                "Call lab phone at [redacted] for pickup",
                "Course announcements and weekly reading schedule",
            ),
            headers,
        )
    }

    @Test
    fun verifiesPurposeLocally() {
        val reelsKind = ScreenKind.ShortForm("Instagram Reels")
        val shortResult = verifyPurposeLocal("study math", "com.instagram.android", reelsKind, emptyList())
        assertTrue(shortResult is PurposeMatch.Mismatch)
        assertEquals("Instagram Reels is blocked during an important-use pass.", (shortResult as PurposeMatch.Mismatch).reason)

        val dmKind = ScreenKind.SafeSection("Instagram DMs")
        val dmMatch = verifyPurposeLocal("dm alice about math hw", "com.instagram.android", dmKind, emptyList())
        assertEquals(PurposeMatch.Match, dmMatch)

        val loadingResult = verifyPurposeLocal("research algorithms", "com.google.android.youtube", ScreenKind.SafeSection("YouTube Video"), emptyList())
        assertEquals(PurposeMatch.Match, loadingResult)

        val matchNodes = listOf(ScreenNode(text = "CSE 100 Lecture 4: Balanced Search Trees"))
        val matchResult = verifyPurposeLocal("watch cse 100 lecture", "com.google.android.youtube", ScreenKind.SafeSection("YouTube Video"), matchNodes)
        assertEquals(PurposeMatch.Match, matchResult)

        // A short-form screen whose visible header matches the stated purpose is allowed during a pass
        val reelMatch = verifyPurposeLocal("watch cse 100 demo", "com.instagram.android", reelsKind, matchNodes)
        assertEquals(PurposeMatch.Match, reelMatch)

        // a purpose word hidden inside another word is not a match
        val collab = listOf(ScreenNode(text = "Collab with Label Records"))
        assertTrue(verifyPurposeLocal("check lab schedule", "com.instagram.android", reelsKind, collab) is PurposeMatch.Uncertain)

        val unrelatedNodes = listOf(ScreenNode(text = "Top 10 Hilarious Cat Compilation 2026"))
        val uncertainResult = verifyPurposeLocal("study cse 100", "com.google.android.youtube", ScreenKind.SafeSection("YouTube Video"), unrelatedNodes)
        assertTrue(uncertainResult is PurposeMatch.Uncertain)
        assertEquals(listOf("Top 10 Hilarious Cat Compilation 2026"), (uncertainResult as PurposeMatch.Uncertain).headers)
    }

    @Test
    fun verifiesPurposeGeminiFailsOpen() {
        assertTrue(verifyPurposeGemini("study", emptyList(), "key"))
        assertTrue(verifyPurposeGemini("study", listOf("header"), ""))

        // Network error fails open
        val failsOpen = verifyPurposeGemini("study", listOf("header"), "key") { _, _, _ ->
            throw java.io.IOException("Network timeout")
        }
        assertTrue(failsOpen)

        // Semantic match
        val matched = verifyPurposeGemini("study", listOf("header"), "key") { _, _, _ ->
            """{"matches":true}"""
        }
        assertTrue(matched)

        // Semantic mismatch
        val rejected = verifyPurposeGemini("study", listOf("header"), "key") { _, _, _ ->
            """{"matches":false}"""
        }
        assertFalse(rejected)
    }

    @Test
    fun detectsFocusEventTitlesAndCourseCodes() {
        assertTrue(isFocusEventTitle("CSE 100 Lecture"))
        assertTrue(isFocusEventTitle("MATH 170A Discussion"))
        assertTrue(isFocusEventTitle("COGS 108 Project Sync"))
        assertTrue(isFocusEventTitle("Study for midterm"))
        assertTrue(isFocusEventTitle("Homework session", "Working on lab 3"))
        assertFalse(isFocusEventTitle("Dentist appointment"))
        assertFalse(isFocusEventTitle("Lunch with Alex"))
        assertFalse(isFocusEventTitle("Dinner at 7"))
        assertFalse(isFocusEventTitle("Collab call"))
    }

    @Test
    fun selectsFocusBlockWithPriority() {
        val calendarEvents = listOf(FocusBlock("COGS 108", null, 1000L))
        val selected = selectFocusBlock(calendarEvents, "Write proof", listOf("Do quiz"))
        assertEquals(FocusBlock("COGS 108", "Write proof", 1000L), selected)

        val withExistingAction = listOf(FocusBlock("COGS 108", "Finish draft", 1000L))
        assertEquals(FocusBlock("COGS 108", "Finish draft", 1000L), selectFocusBlock(withExistingAction, "Write proof"))

        val fromManual = selectFocusBlock(emptyList(), "Finish report", listOf("Fallback item"))
        assertEquals(FocusBlock("Finish report", "Fallback item"), fromManual)

        assertNull(selectFocusBlock(emptyList(), "", listOf("Fallback item")))
    }

    @Test
    fun evaluatesGuardPolicy() {
        val baseConfig = GuardConfig(stage = EvalStage.BASELINE)
        val stats = GuardStats("2026-11-01", shortFormSec = 0)
        val shortKind = ScreenKind.ShortForm("Instagram Reels")

        // Baseline stage allows everything
        assertEquals(GuardAction.Allow, evaluateGuard(baseConfig, shortKind, emptyList(), "com.instagram.android", null, stats, 100L, 0L, "", 0L, 0))

        // Other kind allows
        val returnConfig = GuardConfig(stage = EvalStage.GUARD_RETURN)
        assertEquals(GuardAction.Allow, evaluateGuard(returnConfig, ScreenKind.Other, emptyList(), "pkg", null, stats, 100L, 0L, "", 0L, 0))

        // Safe section allows
        assertEquals(GuardAction.Allow, evaluateGuard(returnConfig, ScreenKind.SafeSection("DMs"), emptyList(), "pkg", null, stats, 100L, 0L, "", 0L, 0))

        // Focus block interrupts short-form
        val focus = FocusBlock("COGS 108")
        val focusInterrupt = evaluateGuard(returnConfig, shortKind, emptyList(), "com.instagram.android", focus, stats, 100L, 0L, "", 0L, 0)
        assertTrue(focusInterrupt is GuardAction.Interrupt)
        val fi = focusInterrupt as GuardAction.Interrupt
        assertEquals("Blocked during COGS 108.", fi.reason)
        assertEquals(focus, fi.focus)
        assertTrue(fi.allowPurposePass)
        assertFalse(fi.allowLeisureBreak)

        // Outside focus block: leisure allowed
        val leisureAllow = evaluateGuard(returnConfig, shortKind, emptyList(), "com.instagram.android", null, stats, 100L, 0L, "", 200L, 2)
        assertEquals(GuardAction.Allow, leisureAllow)

        // Video limit reached interrupts
        val limitInterrupt = evaluateGuard(returnConfig, shortKind, emptyList(), "com.instagram.android", null, stats, 100L, 0L, "", 200L, 5)
        assertTrue(limitInterrupt is GuardAction.Interrupt)
        assertEquals("Reached 5 consecutive short videos.", (limitInterrupt as GuardAction.Interrupt).reason)

        // Budget depleted interrupts
        val usedStats = GuardStats("2026-11-01", shortFormSec = 15 * 60)
        val budgetInterrupt = evaluateGuard(returnConfig, shortKind, emptyList(), "com.instagram.android", null, usedStats, 100L, 0L, "", 200L, 0)
        assertTrue(budgetInterrupt is GuardAction.Interrupt)
        assertEquals("Daily 15m short-form allowance used.", (budgetInterrupt as GuardAction.Interrupt).reason)

        // Accountability consequences trigger after threshold
        val acctConfig = GuardConfig(stage = EvalStage.GUARD_ACCOUNTABILITY, accountabilityMode = AccountabilityMode.CONSEQUENCE, violationThreshold = 2)
        val overrideStats = GuardStats("2026-11-01", overrides = 1)
        val acctInterrupt = evaluateGuard(acctConfig, shortKind, emptyList(), "com.instagram.android", focus, overrideStats, 100L, 0L, "", 0L, 0) as GuardAction.Interrupt
        assertTrue(acctInterrupt.triggerCountdown)
        assertFalse(acctInterrupt.sendSupportiveNotice)

        // Active pass check Gemini when uncertain
        val passNodes = listOf(ScreenNode(text = "Unrelated cooking video"))
        val geminiCheck = evaluateGuard(returnConfig, ScreenKind.SafeSection("YouTube Video"), passNodes, "com.google.android.youtube", null, stats, 100L, 500L, "cse 100", 0L, 0)
        assertTrue(geminiCheck is GuardAction.CheckGemini)

        // False-positive cancel pass allows ShortForm screen without re-interrupting
        assertEquals(GuardAction.Allow, evaluateGuard(returnConfig, shortKind, emptyList(), "com.instagram.android", focus, stats, 100L, 500L, "false positive", 0L, 0))
    }

    @Test
    fun shouldNotifyPartnerEnforcesCooldownAndThreshold() {
        assertFalse(shouldNotifyPartner(EvalStage.GUARD_RETURN, AccountabilityMode.SUPPORTIVE, 3, 2, 0L, 1000L))
        assertFalse(shouldNotifyPartner(EvalStage.GUARD_ACCOUNTABILITY, AccountabilityMode.OFF, 3, 2, 0L, 1000L))
        assertFalse(shouldNotifyPartner(EvalStage.GUARD_ACCOUNTABILITY, AccountabilityMode.SUPPORTIVE, 1, 2, 0L, 1000L))

        // First notification
        assertTrue(shouldNotifyPartner(EvalStage.GUARD_ACCOUNTABILITY, AccountabilityMode.SUPPORTIVE, 2, 2, 0L, 1000L))

        // Too soon (less than 2 hours)
        val twoHours = 2 * 3600_000L
        assertFalse(shouldNotifyPartner(EvalStage.GUARD_ACCOUNTABILITY, AccountabilityMode.SUPPORTIVE, 2, 2, 1000L, 1000L + twoHours - 1000L))

        // Cooldown passed
        assertTrue(shouldNotifyPartner(EvalStage.GUARD_ACCOUNTABILITY, AccountabilityMode.SUPPORTIVE, 2, 2, 1000L, 1000L + twoHours))
    }

    @Test
    fun accountabilityMessagesAreCleanAndNonShaming() {
        val supportive = accountabilityMessage(AccountabilityMode.SUPPORTIVE, "MATH 170A")
        assertTrue(supportive.contains("MATH 170A"))
        assertFalse(supportive.contains("\u2014"))

        val consequence = accountabilityMessage(AccountabilityMode.CONSEQUENCE, null)
        assertTrue(consequence.contains("countdown"))
        assertFalse(consequence.contains("\u2014"))

        assertEquals("", accountabilityMessage(AccountabilityMode.OFF, null))
    }

    @Test
    fun savesAndLoadsGuardConfigAndStats() {
        val cfg = GuardConfig(
            stage = EvalStage.GUARD_ACCOUNTABILITY,
            accountabilityMode = AccountabilityMode.CONSEQUENCE,
            leisureBudgetMin = 20,
            videoLimit = 8,
            violationThreshold = 3,
            focusTask = "Write thesis",
            partnerTarget = "partner@example.com",
            useGeminiVerify = false,
            focusUntilMs = System.currentTimeMillis() + 3600_000L,
        )
        ctx.saveGuardConfig(cfg)
        assertEquals(cfg, ctx.guardConfig())
        // once its hours are up, a typed focus task stops counting as focus time
        ctx.saveGuardConfig(cfg.copy(focusUntilMs = System.currentTimeMillis() - 1))
        assertEquals("", ctx.guardConfig().focusTask)
        ctx.saveGuardConfig(cfg)

        val day = "2026-11-01"
        assertEquals(0, ctx.guardStats(day).shortFormSec)
        ctx.updateGuardStats(day) { it.copy(shortFormSec = 120, overrides = 2) }
        val updated = ctx.guardStats(day)
        assertEquals(120, updated.shortFormSec)
        assertEquals(2, updated.overrides)
        assertEquals(0, ctx.guardStats("2026-11-02").shortFormSec)
        assertEquals(1, ctx.prefs().all.keys.count { it.startsWith("guard_stats") })

        val summary = updated.summaryText(cfg)
        assertTrue(summary.contains("2 / 20m leisure"))
        assertTrue(summary.contains("2 overrides"))
        assertFalse(summary.contains("\u2014"))

        assertFalse(sendAccountabilityNotice(ctx, "", "test"))
        assertFalse(sendAccountabilityNotice(ctx, "   ", "test"))
    }

    @Test
    fun guardActivityRendersAndHandlesPurposePassAndCountdownCancel() {
        val intent = android.content.Intent(ctx, GuardActivity::class.java).apply {
            putExtra("feed", "Instagram Reels")
            putExtra("reason", "Blocked during COGS 108.")
            putExtra("focusTitle", "COGS 108")
            putExtra("nextAction", "Finish section 2")
            putExtra("allowPurposePass", true)
            putExtra("allowLeisureBreak", false)
            putExtra("triggerCountdown", true)
            putExtra("sendSupportiveNotice", false)
        }
        val controller = org.robolectric.Robolectric.buildActivity(GuardActivity::class.java, intent).setup()
        val activity = controller.get()

        assertEquals("Instagram Reels paused", activity.findViewById<android.widget.TextView>(R.id.headline).text.toString())
        assertEquals("Commitment: COGS 108", activity.findViewById<android.widget.TextView>(R.id.focusCommitment).text.toString())
        assertEquals("Next: Finish section 2", activity.findViewById<android.widget.TextView>(R.id.nextAction).text.toString())
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.countdownContainer).visibility)

        // Reveal purpose input and grant 5m pass (which also cancels countdown)
        activity.findViewById<android.widget.Button>(R.id.btnImportant).performClick()
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.purposeInputGroup).visibility)

        // New intent on singleInstance activity refreshes UI and collapses purpose input
        val nextIntent = android.content.Intent(ctx, GuardActivity::class.java).apply {
            putExtra("feed", "YouTube Shorts")
            putExtra("reason", "Daily 15m short-form allowance used.")
            putExtra("allowPurposePass", true)
            putExtra("triggerCountdown", false)
        }
        controller.newIntent(nextIntent)
        assertEquals("YouTube Shorts paused", activity.findViewById<android.widget.TextView>(R.id.headline).text.toString())
        assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.purposeInputGroup).visibility)
        assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.countdownContainer).visibility)

        activity.findViewById<android.widget.Button>(R.id.btnImportant).performClick()
        activity.findViewById<android.widget.EditText>(R.id.purposeInput).setText("message lab partner")
        activity.findViewById<android.widget.Button>(R.id.btnGrantPass).performClick()
        assertEquals("message lab partner", ctx.prefs().getString("guard_pass_intent", ""))
        assertTrue(activity.isFinishing)
        controller.stop().destroy()
    }

    @Test
    fun settingsActivityCyclesGuardStageAndMode() {
        ctx.saveGuardConfig(GuardConfig(stage = EvalStage.GUARD_RETURN, accountabilityMode = AccountabilityMode.SUPPORTIVE))
        val controller = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val stageBtn = activity.findViewById<android.widget.Button>(R.id.guardStage)
        val acctBtn = activity.findViewById<android.widget.Button>(R.id.guardAccountability)
        assertEquals("Stage: 3 · Guard + Return to task", stageBtn.text.toString())
        assertEquals("Accountability: Supportive check-in", acctBtn.text.toString())

        stageBtn.performClick()
        assertEquals(EvalStage.GUARD_ACCOUNTABILITY, ctx.guardConfig().stage)
        assertEquals("Stage: 4 · Guard + Return + Accountability", stageBtn.text.toString())

        acctBtn.performClick()
        assertEquals(AccountabilityMode.CONSEQUENCE, ctx.guardConfig().accountabilityMode)
        assertEquals("Accountability: 30s countdown + notice", acctBtn.text.toString())

        // typing a focus task starts its 3 hours
        activity.findViewById<android.widget.EditText>(R.id.focusTask).setText("Finish lab report")
        controller.pause()
        assertEquals("Finish lab report", ctx.guardConfig().focusTask)
        assertTrue(ctx.guardConfig().focusUntilMs - System.currentTimeMillis() in (FOCUS_TASK_MS - 60_000L)..FOCUS_TASK_MS)
        controller.destroy()
    }

    @Test
    fun guardServiceDebouncesScrollsAndTracksShortFormTime() {
        val svc = org.robolectric.Robolectric.setupService(GuardService::class.java)
        val scroll = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
        val content = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        // Entering short-form starts the timer
        assertTrue(svc.trackShortForm(true, content, 10_000L))
        assertEquals(0, svc.consecVideos)

        // Rapid scroll events within 1.5s count as only 1 video swipe
        assertFalse(svc.trackShortForm(true, scroll, 10_200L))
        assertFalse(svc.trackShortForm(true, scroll, 10_500L))
        assertFalse(svc.trackShortForm(true, scroll, 11_000L))
        assertEquals(1, svc.consecVideos)

        // Next swipe after 1.5s increments to 2
        assertFalse(svc.trackShortForm(true, scroll, 12_000L))
        assertEquals(2, svc.consecVideos)

        // Leaving short-form flushes elapsed seconds (5s) and resets video count
        assertFalse(svc.trackShortForm(false, content, 15_000L))
        assertEquals(0, svc.consecVideos)
        assertEquals(5, svc.guardStats().shortFormSec)

        // Notifications and keyboard events do not reset short-form streak; foreground app switches do
        val winState = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        assertFalse(svc.shouldResetOnNonGuardedEvent("com.android.systemui", winState))
        assertFalse(svc.shouldResetOnNonGuardedEvent("com.samsung.android.honeyboard", winState))
        assertFalse(svc.shouldResetOnNonGuardedEvent("com.android.chrome", content))
        assertTrue(svc.shouldResetOnNonGuardedEvent("com.android.chrome", winState))

        // At most one Gemini check every 20 s, even as each swipe brings new screen text
        assertTrue(svc.shouldCheckGemini(1_000L))
        assertFalse(svc.shouldCheckGemini(5_000L))
        assertFalse(svc.shouldCheckGemini(20_000L))
        assertTrue(svc.shouldCheckGemini(21_000L))

        // Exact accessibility component matching
        assertTrue(isServiceInEnabledList("com.other/.Svc:app.thoughtcapturer/app.thoughtcapturer.GuardService", "app.thoughtcapturer"))
        assertTrue(isServiceInEnabledList("app.thoughtcapturer/.GuardService", "app.thoughtcapturer"))
        assertFalse(isServiceInEnabledList("app.thoughtcapturer.other/.GuardService", "app.thoughtcapturer"))
    }

    @Test
    fun countdownSendsNoSecondNoticeWithinTwoHours() {
        ctx.saveGuardConfig(GuardConfig(stage = EvalStage.GUARD_ACCOUNTABILITY, accountabilityMode = AccountabilityMode.CONSEQUENCE, violationThreshold = 2, partnerTarget = "+15555550100"))
        ctx.updateGuardStats { it.copy(overrides = 1) }
        ctx.prefs().edit().putLong("guard_last_notified", System.currentTimeMillis() - 3600_000L).commit()
        val intent = android.content.Intent(ctx, GuardActivity::class.java).putExtra("triggerCountdown", true)
        val activity = org.robolectric.Robolectric.buildActivity(GuardActivity::class.java, intent).setup().get()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
        assertEquals("Your partner was already told in the last 2 hours.", activity.findViewById<android.widget.TextView>(R.id.countdownText).text.toString())
        assertEquals(0, ctx.guardStats().notificationsSent)
    }

    @Test
    fun lockedScreenTimeIsNotCountedAsWatching() {
        val svc = org.robolectric.Robolectric.setupService(GuardService::class.java)
        val content = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        svc.trackShortForm(true, content, 0L)
        svc.trackShortForm(true, content, 2 * 3600_000L) // unlocked two hours later, still on the same Reel
        assertEquals(10, svc.guardStats().shortFormSec)
        svc.trackShortForm(false, content, 4 * 3600_000L) // the same gap right before leaving
        assertEquals(20, svc.guardStats().shortFormSec)
    }
}
