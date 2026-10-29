package app.thoughtcapturer

import android.app.Activity
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import java.io.IOException
import kotlin.concurrent.thread
import org.json.JSONObject

class SettingsActivity : Activity() {
    private lateinit var account: TextView
    private lateinit var key: EditText
    private var signingIn = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.settings)
        account = findViewById(R.id.account)
        key = findViewById(R.id.key)
        key.setText(prefs().getString("gemini_key", ""))
        findViewById<View>(R.id.signin).setOnClickListener { if (!signingIn) signIn() }
    }

    override fun onResume() {
        super.onResume()
        if (!signingIn) refresh()
    }

    override fun onPause() {
        super.onPause()
        prefs().edit().putString("gemini_key", key.text.toString().trim()).apply()
    }

    private fun refresh() {
        val p = prefs()
        account.text = if (p.getString("refresh_token", null) != null) "Signed in. Captures go to your Tasks list." else "Not signed in."
        val pending = getSystemService(JobScheduler::class.java).allPendingJobs.size
        findViewById<TextView>(R.id.errors).text = listOfNotNull(
            "$pending capture(s) waiting to sync.".takeIf { pending > 0 },
            p.getString("last_error", null)?.let { "Last sync error: $it" },
            p.getString("gemini_error", null)?.let { "Last Gemini error (that capture was saved unsplit): $it" },
        ).joinToString("\n\n")
    }

    /** Device code sign-in, same flow as canvas-todo-sync --login, so no redirect URI or signing hash is needed. */
    private fun signIn() {
        signingIn = true
        account.text = "Contacting Microsoft…"
        thread {
            val failure = try {
                val d = http("$MS_LOGIN/devicecode", form("client_id" to MS_CLIENT_ID, "scope" to MS_SCOPE), "application/x-www-form-urlencoded")
                val code = d.getString("user_code")
                val url = d.getString("verification_uri")
                runOnUiThread {
                    account.text = "Enter code $code at $url (it's copied). Then come back here."
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                pollForToken(d)
                SaveJob.retryAll(this)
                null
            } catch (e: Exception) {
                "Sign-in failed: ${e.message}"
            }
            runOnUiThread {
                signingIn = false
                refresh()
                if (failure != null) account.text = failure
            }
        }
    }

    private fun pollForToken(d: JSONObject) {
        val deadline = System.currentTimeMillis() + d.getLong("expires_in") * 1000
        var wait = d.optLong("interval", 5) * 1000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(wait)
            try {
                val t = msToken("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to d.getString("device_code"))
                prefs().edit().putString("refresh_token", t.getString("refresh_token")).remove("last_error").apply()
                return
            } catch (e: HttpError) {
                when {
                    "authorization_pending" in e.body -> Unit
                    "slow_down" in e.body -> wait += 5000
                    else -> throw e
                }
            }
        }
        throw IOException("the code expired. Tap Sign in again.")
    }
}
