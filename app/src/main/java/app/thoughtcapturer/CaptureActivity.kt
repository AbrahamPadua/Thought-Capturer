package app.thoughtcapturer

import android.Manifest.permission.RECORD_AUDIO
import android.animation.ValueAnimator
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private const val LISTENING = "Listening. Tap Done when you're finished."
private const val TYPING = "Typing. It's still listening."

/**
 * Opens already listening and keeps listening until Done (save) or Cancel (discard). Pauses and taps
 * don't stop it. Leaving (back, home, flipped shut) saves. The pulse plot shows it's hearing you.
 */
class CaptureActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var catalog: TextView
    private lateinit var strip: LinearLayout
    private lateinit var text: EditText
    private lateinit var now: TextView
    private lateinit var plot: PulsePlotView
    private var recognizer: SpeechRecognizer? = null
    private var closing = false
    private var askingPermission = false
    private var micError = false

    // the job id this capture will get, shown as its catalog number
    private val number by lazy { prefs().getInt("next_job", 1) }

    private val notice by lazy {
        val p = prefs()
        val pending = getSystemService(JobScheduler::class.java).allPendingJobs.size
        val error = p.getString("last_error", null)
        when {
            p.getString("refresh_token", null) == null -> "Not signed in to Microsoft yet. Open settings."
            pending > 0 && error != null -> "$pending waiting to sync: $error"
            else -> null
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.capture)
        status = findViewById(R.id.status)
        catalog = findViewById(R.id.catalog)
        strip = findViewById(R.id.strip)
        text = findViewById(R.id.text)
        now = findViewById(R.id.now)
        plot = findViewById(R.id.plot)
        catalog.text = "Capture · %03d".format(number)
        strip.showStatus(status())
        text.setOnFocusChangeListener { _, focused ->
            show(if (focused) TYPING else LISTENING)
            // one prompt at a time: "Type here" in the box while typing, "Start talking" otherwise
            text.hint = if (focused) "Type here" else null
            now.hint = if (focused || text.text.isNotBlank()) null else "Start talking"
            // typed words are the current sentence, so white like spoken ones; the gray is for what's already said
            text.setTextColor(getColor(if (focused) R.color.ink_line else R.color.ink_muted))
        }
        findViewById<View>(R.id.done).setOnClickListener { save(animate = true) }
        findViewById<View>(R.id.type).setOnClickListener { type() }
        findViewById<View>(R.id.cancel).setOnClickListener {
            closing = true
            finish()
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            save(animate = false)
            // own task: this screen's task is hidden from Recents, and Settings must be easy to return to from the browser
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // after a speech error the plot is the way back to listening
        plot.setOnClickListener { if (micError) start() }
        plot.isClickable = false
        SettingsActivity.pollSignIn(applicationContext)
        show(LISTENING)
        start()
    }

    private fun start() {
        if (checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return listen()
        askingPermission = true
        requestPermissions(arrayOf(RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        askingPermission = false
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else micProblem("No microphone permission. Type instead, or tap the lines to ask again.")
    }

    private fun listen() {
        if (closing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return micProblem("No speech recognizer on this phone. Type instead.")
        micError = false
        plot.isClickable = false
        plot.contentDescription = null
        strip.showStatus(status())
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        r.cancel() // restarting while already listening would otherwise fail as busy
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    private fun micProblem(msg: String) {
        micError = true
        plot.listening = false
        plot.isClickable = true
        plot.contentDescription = "Listen again"
        strip.showStatus(status(micError = true))
        show(msg)
    }

    private fun type() {
        text.requestFocus()
        text.setSelection(text.length())
        getSystemService(InputMethodManager::class.java).showSoftInput(text, 0)
    }

    private fun show(msg: String) {
        status.text = listOfNotNull(notice, msg).joinToString("\n")
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun onReadyForSpeech(params: Bundle?) {
        plot.listening = true
        show(if (text.hasFocus()) TYPING else LISTENING)
    }

    override fun onRmsChanged(rmsdB: Float) = plot.setLevel(rmsdB)

    // words still being heard sit large and white; finished sentences move up into the gray text above
    override fun onPartialResults(b: Bundle?) {
        best(b)?.let { now.text = it }
    }

    override fun onResults(b: Bundle?) {
        if (closing) return
        best(b)?.takeIf { it.isNotBlank() }?.let {
            text.append(if (text.text.isBlank() || text.text.endsWith(" ")) it else " $it")
            plot.freeze()
            now.hint = null // you've started; no need to prompt again
        }
        now.text = ""
        // the recognizer ends after each sentence; start the next one right away
        text.postDelayed({ listen() }, 250)
    }

    override fun onError(error: Int) {
        if (closing) return
        when (error) {
            // silence is a pause, not the end: keep listening until Done
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                text.postDelayed({ listen() }, 250)
            else -> micProblem("Speech error $error. Tap the lines to listen again, or type.")
        }
    }

    private fun save(animate: Boolean) {
        if (closing) return
        closing = true
        recognizer?.cancel()
        plot.listening = false
        // include words still being heard: tapping Done mid-sentence must not drop them
        val t = listOf(text.text, now.text).joinToString(" ").trim()
        val queued = t.isNotEmpty() && SaveJob.enqueue(this, t)
        if (t.isNotEmpty() && !queued) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("capture", t))
            Toast.makeText(applicationContext, "Couldn't queue it. Text copied to clipboard.", Toast.LENGTH_LONG).show()
        }
        if (queued && animate && ValueAnimator.areAnimatorsEnabled()) {
            catalog.text = "%03d filed".format(number)
            status.text = "Filed."
            now.hint = null
            plot.fold { finish() }
            return
        }
        if (queued) Toast.makeText(applicationContext, "Captured", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // pressed back or home, or flipped shut: keep what was said
        if (!isChangingConfigurations && !askingPermission) save(animate = false)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
