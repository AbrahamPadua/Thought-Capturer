package app.thoughtcapturer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Sign-in state lives in prefs, not in this screen, so a sign-in started here still finishes after the
 * screen is rebuilt (flipping the phone), reopened from the icon, or the app was frozen in the background.
 */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var account: TextView
    private lateinit var key: EditText
    private lateinit var guardStatus: TextView
    private lateinit var guardEnable: Button
    private lateinit var guardCalendar: Button
    private lateinit var guardStage: Button
    private lateinit var guardAccountability: Button
    private lateinit var focusTask: EditText
    private lateinit var leisureBudget: EditText
    private lateinit var partnerTarget: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.settings)
        account = findViewById(R.id.account)
        key = findViewById(R.id.key)
        key.setText(prefs().getString("gemini_key", ""))
        findViewById<View>(R.id.signin).setOnClickListener { signIn() }

        guardStatus = findViewById(R.id.guardStatus)
        guardEnable = findViewById(R.id.guardEnable)
        guardCalendar = findViewById(R.id.guardCalendar)
        guardStage = findViewById(R.id.guardStage)
        guardAccountability = findViewById(R.id.guardAccountability)
        focusTask = findViewById(R.id.focusTask)
        leisureBudget = findViewById(R.id.leisureBudget)
        partnerTarget = findViewById(R.id.partnerTarget)

        val cfg = guardConfig()
        focusTask.setText(cfg.focusTask)
        leisureBudget.setText(cfg.leisureBudgetMin.toString())
        partnerTarget.setText(cfg.partnerTarget)

        guardEnable.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        guardCalendar.setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 102)
        }

        guardStage.setOnClickListener {
            val current = guardConfig()
            val stages = EvalStage.entries
            val next = stages[(current.stage.ordinal + 1) % stages.size]
            saveGuardConfig(current.copy(stage = next))
            refresh()
        }

        guardAccountability.setOnClickListener {
            val current = guardConfig()
            val modes = AccountabilityMode.entries
            val next = modes[(current.accountabilityMode.ordinal + 1) % modes.size]
            saveGuardConfig(current.copy(accountabilityMode = next))
            val target = partnerTarget.text.toString().trim()
            if (next != AccountabilityMode.OFF && !target.startsWith("http") && target.any { it.isDigit() }) {
                if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 101)
                }
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs().registerOnSharedPreferenceChangeListener(this)
        refresh()
        pollSignIn(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        prefs().unregisterOnSharedPreferenceChangeListener(this)
        prefs().edit().putString("gemini_key", key.text.toString().trim()).apply()
        val cfg = guardConfig()
        val budget = leisureBudget.text.toString().toIntOrNull() ?: cfg.leisureBudgetMin
        val task = focusTask.text.toString().trim()
        saveGuardConfig(cfg.copy(
            focusTask = task,
            // the clock starts when the task is typed or changed, not each time Settings closes
            focusUntilMs = if (task != cfg.focusTask) System.currentTimeMillis() + FOCUS_TASK_MS else cfg.focusUntilMs,
            leisureBudgetMin = budget,
            partnerTarget = partnerTarget.text.toString().trim(),
        ))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, changed: String?) = refresh()

    private fun refresh() {
        val p = prefs()
        val code = p.getString("user_code", null)
        val signedIn = p.getString("refresh_token", null) != null
        findViewById<TextView>(R.id.code).apply {
            text = code
            visibility = if (code != null) View.VISIBLE else View.GONE
        }
        account.text = when {
            code != null -> "Enter this code at ${p.getString("verification_uri", null)}. It's on your clipboard. Then come back here."
            signedIn -> "Signed in. To-dos go to your To Do Tasks list, notes to the Capture section in OneNote."
            else -> p.getString("signin_error", null) ?: "Not signed in."
        }
        findViewById<Button>(R.id.signin).text = if (signedIn) "Sign in again" else "Sign in to Microsoft"
        val s = status()
        findViewById<LinearLayout>(R.id.queueStrip).showStatus(s)
        findViewById<TextView>(R.id.errors).text = listOfNotNull(
            s.describe().ifEmpty { "Nothing waiting." },
            p.getString("last_error", null)?.let { "Last sync error: $it" },
            p.getString("gemini_error", null)?.let { "Last Gemini error (that capture was saved unsplit): $it" },
        ).joinToString("\n\n")

        val cfg = guardConfig()
        val stats = guardStats()
        val serviceActive = isGuardServiceEnabled()
        val calendarGranted = checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        val serviceText = if (serviceActive) "Guard accessibility service active." else "Guard accessibility service disabled."
        val calText = if (calendarGranted) "Calendar access granted." else "Calendar access not granted."
        val focusText = if (cfg.focusTask.isBlank()) "" else
            " Focus task until ${android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(cfg.focusUntilMs))}."
        val hookText = if (cfg.partnerTarget.startsWith("http://", ignoreCase = true)) " The partner webhook must start with https://." else ""
        guardStatus.text = "$serviceText $calText$focusText$hookText\n\n${stats.summaryText(cfg)}"

        guardEnable.text = if (serviceActive) "Accessibility settings (active)" else "Enable Guard in Accessibility"
        guardCalendar.visibility = if (calendarGranted) View.GONE else View.VISIBLE
        guardStage.text = "Stage: ${cfg.stage.label}"
        guardAccountability.text = "Accountability: ${cfg.accountabilityMode.label}"
    }

    /** Device code sign-in, same flow as canvas-todo-sync --login, so no redirect URI or signing hash is needed. */
    private fun signIn() {
        account.text = "Contacting Microsoft…"
        thread {
            try {
                val d = http("$MS_LOGIN/devicecode", form("client_id" to MS_CLIENT_ID, "scope" to MS_SCOPE), "application/x-www-form-urlencoded")
                val code = d.getString("user_code")
                val url = d.getString("verification_uri")
                prefs().edit()
                    .putString("device_code", d.getString("device_code"))
                    .putString("user_code", code)
                    .putString("verification_uri", url)
                    .putLong("code_expires", System.currentTimeMillis() + d.getLong("expires_in") * 1000)
                    .putLong("poll_interval", d.optLong("interval", 5) * 1000)
                    .remove("signin_error")
                    .commit()
                runOnUiThread {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                pollSignIn(applicationContext)
            } catch (e: Exception) {
                prefs().edit().putString("signin_error", "Sign-in failed: ${e.message}").commit()
                // prefs only notify on a changed value, so the same error twice would leave "Contacting Microsoft…" up
                runOnUiThread { refresh() }
            }
        }
    }

    companion object {
        private val polling = AtomicBoolean(false)

        /** Waits for the pending device code to be approved. One poller per process; it rereads prefs every round. */
        fun pollSignIn(ctx: Context) {
            if (polling.compareAndSet(false, true)) thread {
                try {
                    waitForApproval(ctx)
                } finally {
                    polling.set(false)
                }
            }
        }

        /** [redeem] asks Microsoft for tokens with a device code; tests pass a fake. */
        internal fun waitForApproval(
            ctx: Context,
            redeem: (String) -> JSONObject = { msToken("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to it) },
        ) {
            val p = ctx.prefs()
            while (true) {
                val code = p.getString("device_code", null) ?: return
                val error = if (System.currentTimeMillis() > p.getLong("code_expires", 0)) "The code expired. Tap Sign in again." else try {
                    val t = redeem(code)
                    clearCode(p.edit()).putString("refresh_token", t.getString("refresh_token"))
                        .remove("signin_error").remove("last_error").commit()
                    SaveJob.retryAll(ctx)
                    return
                } catch (e: HttpError) {
                    if ("slow_down" in e.body) p.edit().putLong("poll_interval", p.getLong("poll_interval", 5000) + 5000).apply()
                    if ("authorization_pending" in e.body || "slow_down" in e.body) null else "Sign-in failed: ${e.message}"
                } catch (e: IOException) {
                    null // no network, or the app sat frozen in the background: keep trying until the code expires
                } catch (e: Exception) {
                    "Sign-in failed: ${e.message}"
                }
                if (error == null) Thread.sleep(p.getLong("poll_interval", 5000))
                // only clear the code we were polling; a newer Sign in tap may have replaced it
                else if (code == p.getString("device_code", null)) clearCode(p.edit()).putString("signin_error", error).commit()
            }
        }

        private fun clearCode(e: SharedPreferences.Editor) =
            e.remove("device_code").remove("user_code").remove("verification_uri").remove("code_expires").remove("poll_interval")
    }
}
