package app.thoughtcapturer

import android.app.Activity
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sign-in state lives in prefs, not in this screen, so a sign-in started here still finishes after the
 * screen is rebuilt (flipping the phone), reopened from the icon, or the app was frozen in the background.
 */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var account: TextView
    private lateinit var key: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.settings)
        account = findViewById(R.id.account)
        key = findViewById(R.id.key)
        key.setText(prefs().getString("gemini_key", ""))
        findViewById<View>(R.id.signin).setOnClickListener { signIn() }
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
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, changed: String?) = refresh()

    private fun refresh() {
        val p = prefs()
        val code = p.getString("user_code", null)
        account.text = when {
            code != null -> "Enter code $code at ${p.getString("verification_uri", null)} (it's copied). Then come back here."
            p.getString("refresh_token", null) != null -> "Signed in. Captures go to your Tasks list."
            else -> p.getString("signin_error", null) ?: "Not signed in."
        }
        val pending = getSystemService(JobScheduler::class.java).allPendingJobs.size
        findViewById<TextView>(R.id.errors).text = listOfNotNull(
            "$pending capture(s) waiting to sync.".takeIf { pending > 0 },
            p.getString("last_error", null)?.let { "Last sync error: $it" },
            p.getString("gemini_error", null)?.let { "Last Gemini error (that capture was saved unsplit): $it" },
        ).joinToString("\n\n")
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
                prefs().edit().putString("signin_error", "Sign-in failed: ${e.message}").apply()
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

        private fun waitForApproval(ctx: Context) {
            val p = ctx.prefs()
            while (true) {
                val code = p.getString("device_code", null) ?: return
                val error = if (System.currentTimeMillis() > p.getLong("code_expires", 0)) "The code expired. Tap Sign in again." else try {
                    val t = msToken("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to code)
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
