package app.thoughtcapturer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Interruption screen following Pulse Sleeve aesthetic.
 * Presents clear recovery options: return to task, capture a thought,
 * request a purpose pass, or take an intentional leisure break.
 */
class GuardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 30
    private var focusTitle: String? = null
    private lateinit var countdownText: TextView

    private val countdownRunnable = object : Runnable {
        override fun run() {
            countdownSeconds--
            if (countdownSeconds > 0) {
                countdownText.text = "${countdownSeconds}s to return to task before accountability notice is sent"
                handler.postDelayed(this, 1000L)
            } else {
                countdownText.text = "Accountability notice sent."
                sendNotice()
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.guard)

        val feed = intent.getStringExtra("feed") ?: "Short-form feed"
        val reason = intent.getStringExtra("reason") ?: "Focus session active"
        focusTitle = intent.getStringExtra("focusTitle")
        val nextAction = intent.getStringExtra("nextAction")
        val allowPurposePass = intent.getBooleanExtra("allowPurposePass", false)
        val allowLeisureBreak = intent.getBooleanExtra("allowLeisureBreak", false)
        val leisureRemainingMin = intent.getIntExtra("leisureRemainingMin", 0)
        val triggerCountdown = intent.getBooleanExtra("triggerCountdown", false)
        val sendSupportiveNotice = intent.getBooleanExtra("sendSupportiveNotice", false)

        val guardLabel = findViewById<TextView>(R.id.guardLabel)
        guardLabel.text = if (allowLeisureBreak) "GUARD · LEISURE" else "GUARD · FOCUS"

        val guardStrip = findViewById<LinearLayout>(R.id.guardStrip)
        setupCodeStrip(guardStrip, allowPurposePass || allowLeisureBreak)

        val headline = findViewById<TextView>(R.id.headline)
        headline.text = "$feed paused"

        val reasonView = findViewById<TextView>(R.id.reason)
        reasonView.text = reason

        val cfg = guardConfig()
        val title = focusTitle ?: cfg.focusTask.ifBlank { "Focus session" }
        findViewById<TextView>(R.id.focusCommitment).text = "Commitment: $title"

        val nextActionView = findViewById<TextView>(R.id.nextAction)
        if (!nextAction.isNullOrBlank()) {
            nextActionView.text = "Next: $nextAction"
            nextActionView.visibility = View.VISIBLE
        } else {
            nextActionView.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnReturnToTask).setOnClickListener {
            cancelCountdown()
            updateGuardStats { it.copy(returnedToTask = it.returnedToTask + 1) }
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }

        findViewById<Button>(R.id.btnCaptureThought).setOnClickListener {
            cancelCountdown()
            startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        setupPurposeSection(allowPurposePass, sendSupportiveNotice)
        setupLeisureSection(allowLeisureBreak, leisureRemainingMin)
        setupSupportiveBanner(sendSupportiveNotice)
        setupCountdown(triggerCountdown)
    }

    private fun setupCodeStrip(strip: LinearLayout, hasOption: Boolean) {
        val colors = buildList {
            add(R.color.code_error)
            if (focusTitle != null) add(R.color.code_todo)
            if (hasOption) add(R.color.code_note)
        }
        val desc = listOfNotNull(
            "Interrupted feed",
            "Focus task active".takeIf { focusTitle != null },
            "Pass or leisure option available".takeIf { hasOption },
        ).joinToString(". ")
        strip.showBlocks(colors, desc)
    }

    private fun setupPurposeSection(allowPurposePass: Boolean, sendSupportiveNotice: Boolean) {
        val container = findViewById<LinearLayout>(R.id.purposeContainer)
        if (!allowPurposePass) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val btnImportant = findViewById<Button>(R.id.btnImportant)
        val inputGroup = findViewById<LinearLayout>(R.id.purposeInputGroup)
        val input = findViewById<EditText>(R.id.purposeInput)
        val btnGrantPass = findViewById<Button>(R.id.btnGrantPass)

        btnImportant.setOnClickListener {
            inputGroup.visibility = View.VISIBLE
            input.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        btnGrantPass.setOnClickListener {
            val purpose = input.text.toString().trim()
            if (purpose.isBlank()) {
                input.error = "Please state your purpose"
                return@setOnClickListener
            }
            cancelCountdown()
            prefs().edit()
                .putLong("guard_pass_until", System.currentTimeMillis() + 5 * 60_000L)
                .putString("guard_pass_intent", purpose)
                .apply()
            updateGuardStats { it.copy(overrides = it.overrides + 1) }

            if (sendSupportiveNotice) {
                val cfg = guardConfig()
                val lastNotified = prefs().getLong("guard_last_notified", 0L)
                val stats = guardStats()
                if (shouldNotifyPartner(cfg.stage, cfg.accountabilityMode, stats.overrides, cfg.violationThreshold, lastNotified, System.currentTimeMillis())) {
                    thread {
                        val msg = accountabilityMessage(cfg.accountabilityMode, focusTitle)
                        if (sendAccountabilityNotice(this, cfg.partnerTarget, msg)) {
                            prefs().edit().putLong("guard_last_notified", System.currentTimeMillis()).apply()
                            updateGuardStats { it.copy(notificationsSent = it.notificationsSent + 1) }
                        }
                    }
                }
            }
            finish()
        }
    }

    private fun setupLeisureSection(allowLeisureBreak: Boolean, leisureRemainingMin: Int) {
        val container = findViewById<LinearLayout>(R.id.leisureContainer)
        if (!allowLeisureBreak || leisureRemainingMin <= 0) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val btnLeisure = findViewById<Button>(R.id.btnLeisure)
        btnLeisure.text = "Take 5m leisure ($leisureRemainingMin min left)"
        btnLeisure.setOnClickListener {
            cancelCountdown()
            prefs().edit()
                .putLong("guard_leisure_until", System.currentTimeMillis() + 5 * 60_000L)
                .apply()
            finish()
        }
    }

    private fun setupSupportiveBanner(sendSupportiveNotice: Boolean) {
        val banner = findViewById<TextView>(R.id.supportiveBanner)
        val cfg = guardConfig()
        val stats = guardStats()
        val lastNotified = prefs().getLong("guard_last_notified", 0L)
        if (sendSupportiveNotice && shouldNotifyPartner(cfg.stage, cfg.accountabilityMode, stats.overrides, cfg.violationThreshold, lastNotified, System.currentTimeMillis())) {
            banner.text = "Your focus commitment is at risk."
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun setupCountdown(triggerCountdown: Boolean) {
        val container = findViewById<LinearLayout>(R.id.countdownContainer)
        if (!triggerCountdown) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        countdownText = findViewById(R.id.countdownText)
        countdownSeconds = 30
        countdownText.text = "${countdownSeconds}s to return to task before accountability notice is sent"

        findViewById<Button>(R.id.btnCountdownReturn).setOnClickListener {
            cancelCountdown()
            updateGuardStats { it.copy(returnedToTask = it.returnedToTask + 1) }
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }

        findViewById<Button>(R.id.btnCountdownCancel).setOnClickListener {
            cancelCountdown()
            container.visibility = View.GONE
            prefs().edit()
                .putLong("guard_pass_until", System.currentTimeMillis() + 5 * 60_000L)
                .putString("guard_pass_intent", "false positive")
                .apply()
            finish()
        }

        handler.postDelayed(countdownRunnable, 1000L)
    }

    private fun sendNotice() {
        thread {
            val cfg = guardConfig()
            val msg = accountabilityMessage(cfg.accountabilityMode, focusTitle)
            if (sendAccountabilityNotice(this, cfg.partnerTarget, msg)) {
                prefs().edit().putLong("guard_last_notified", System.currentTimeMillis()).apply()
                updateGuardStats { it.copy(notificationsSent = it.notificationsSent + 1) }
            }
        }
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(countdownRunnable)
    }

    override fun onDestroy() {
        cancelCountdown()
        super.onDestroy()
    }
}
