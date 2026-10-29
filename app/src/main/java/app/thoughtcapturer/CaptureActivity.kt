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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Opens already listening and keeps listening until Done (save) or Cancel (discard). Pauses, taps on the
 * text box and touches around the dialog don't stop it. Leaving (back, home, flipped shut) saves.
 */
class CaptureActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var text: EditText
    private var recognizer: SpeechRecognizer? = null
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
        // on the small cover screen a stray touch next to the dialog is easy; it must not end the recording
        setFinishOnTouchOutside(false)
        status = findViewById(R.id.status)
        text = findViewById(R.id.text)
        findViewById<View>(R.id.done).setOnClickListener { save() }
        findViewById<View>(R.id.mic).setOnClickListener { listen() }
        findViewById<View>(R.id.cancel).setOnClickListener {
            closing = true
            finish()
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            save()
            // own task: this dialog's task is hidden from Recents, and Settings must be easy to return to from the browser
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        SettingsActivity.pollSignIn(applicationContext)
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
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        r.cancel() // 🎤 while already listening restarts instead of failing as busy
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

    override fun onReadyForSpeech(params: Bundle?) = show("Listening… tap Done when you're finished.")

    // live words go in the status line, so they never overwrite what you're typing in the box
    override fun onPartialResults(b: Bundle?) {
        best(b)?.let { show("Listening… $it") }
    }

    override fun onResults(b: Bundle?) {
        if (closing) return
        best(b)?.takeIf { it.isNotBlank() }?.let { text.append(if (text.text.isBlank() || text.text.endsWith(" ")) it else " $it") }
        show("Listening… tap Done when you're finished.")
        // the recognizer ends after each sentence; start the next one right away
        text.postDelayed({ listen() }, 250)
    }

    override fun onError(error: Int) {
        if (closing) return
        when (error) {
            // silence is a pause, not the end: keep listening until Done
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                text.postDelayed({ listen() }, 250)
            else -> show("Speech error $error. Tap 🎤 to listen again, or type.")
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
        // pressed back or home, or flipped shut: keep what was said
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
