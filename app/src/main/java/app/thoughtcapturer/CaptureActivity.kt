package app.thoughtcapturer

import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Opens already listening. Saves when you go quiet, tap Done, or leave; only Cancel throws it away. */
class CaptureActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var text: EditText
    private var recognizer: SpeechRecognizer? = null
    private var heard = "" // text before the current speech segment
    private var closing = false
    private var askingPermission = false

    private val notice by lazy {
        val p = prefs()
        val pending = getSystemService(JobScheduler::class.java).allPendingJobs.size
        val error = p.getString("last_error", null)
        when {
            p.getString("refresh_token", null) == null -> "Not signed in to Microsoft yet. Tap ⚙."
            pending > 0 && error != null -> "$pending waiting to sync: $error"
            else -> null
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.capture)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        status = findViewById(R.id.status)
        text = findViewById(R.id.text)
        text.setOnFocusChangeListener { _, focused ->
            if (focused) {
                recognizer?.cancel()
                show("Typing. Tap Done to save.")
            }
        }
        findViewById<View>(R.id.done).setOnClickListener { save() }
        findViewById<View>(R.id.mic).setOnClickListener { listen() }
        findViewById<View>(R.id.cancel).setOnClickListener {
            closing = true
            finish()
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            save()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        if (checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else {
            askingPermission = true
            requestPermissions(arrayOf(RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        askingPermission = false
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else show("No microphone permission. Type instead.")
    }

    private fun listen() {
        if (closing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return show("No speech recognizer on this phone. Type instead.")
        if (text.hasFocus()) {
            text.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(text.windowToken, 0)
        }
        heard = text.text.toString()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    private fun show(msg: String) {
        status.text = listOfNotNull(notice, msg).joinToString("\n")
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    override fun onReadyForSpeech(params: Bundle?) = show("Listening… pause a few seconds or tap Done to save.")

    override fun onPartialResults(b: Bundle?) {
        val partial = best(b) ?: return
        if (!text.hasFocus()) text.setText(join(heard, partial))
    }

    override fun onResults(b: Bundle?) {
        if (closing || text.hasFocus()) return
        text.setText(join(heard, best(b).orEmpty()))
        // keep listening through pauses; the silence timeout on the next round is what saves
        text.post { listen() }
    }

    override fun onError(error: Int) {
        if (closing || text.hasFocus()) return
        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        when {
            silence && text.text.isNotBlank() -> save()
            silence -> show("Didn't catch that. Tap 🎤 or type.")
            else -> show("Speech error $error. Tap 🎤 or type.")
        }
    }

    private fun save() {
        if (closing) return
        closing = true
        recognizer?.cancel()
        val t = text.text.toString().trim()
        if (t.isNotEmpty()) {
            if (SaveJob.enqueue(this, t)) Toast.makeText(applicationContext, "Captured", Toast.LENGTH_SHORT).show()
            else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("capture", t))
                Toast.makeText(applicationContext, "Couldn't queue it. Text copied to clipboard.", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // tapped outside, pressed back or home, or flipped shut: keep what was said
        if (!isChangingConfigurations && !askingPermission) save()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
