package app.thoughtcapturer

import android.Manifest.permission.RECORD_AUDIO
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.media.AudioFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

private const val LISTENING = "Listening. Tap Done when you're finished."
private const val LISTENING_NO_KEY = "Listening. Add a Gemini key in Settings for accurate words."
private const val TYPING = "Typing. It's still listening."

/** Where the shown end of [text] starts: about its last [max] characters, from a word start, so the newest words always show. */
internal fun tailFrom(text: CharSequence, max: Int): Int {
    if (text.length <= max) return 0
    var cut = text.indexOf(' ', text.length - max).takeIf { it >= 0 } ?: (text.length - max)
    while (cut < text.length && text[cut] == ' ') cut++
    return cut
}

private class Sentence(val rough: String, var exact: String? = null)

/**
 * Opens already listening and records continuously until Done (save) or Cancel (discard): one recording, never
 * restarted. While you talk, Android's recognizer shows rough words from that same recording; at each pause the
 * sentence's audio goes to Gemini and its exact words replace the rough ones. The upload job transcribes the
 * whole recording again, so what gets filed never depends on the live captions. Leaving (back, home, shut) saves.
 */
class CaptureActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var catalog: TextView
    private lateinit var strip: LinearLayout
    private lateinit var said: TextView
    private lateinit var typed: EditText
    private lateinit var now: TextView
    private lateinit var plot: PulsePlotView
    private var recorder: Recorder? = null
    private var recognizer: SpeechRecognizer? = null
    private val corrector = Executors.newSingleThreadExecutor()
    private val sentences = mutableListOf<Sentence>()
    private val roughWords = RoughWords()
    private var closing = false
    private var askingPermission = false
    private var micError = false
    private var message = "" // the status line, kept so a layout rebuild can show it again

    private val key get() = prefs().getString("gemini_key", null)?.takeIf { it.isNotBlank() }

    // the job id this capture will get, shown as its catalog number
    private val number by lazy { prefs().getInt("next_job", 1) }

    private val notice by lazy {
        val p = prefs()
        val s = status() // the same count and error the code strip shows
        when {
            p.getString("refresh_token", null) == null -> "Not signed in to Microsoft yet. Open settings."
            s.syncError -> "${s.waiting} waiting to sync: ${p.getString("last_error", null)}"
            else -> null
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        bind()
        SettingsActivity.pollSignIn(applicationContext)
        StickyWidget.refresh(this) // an app update leaves the widget showing its old views until something redraws it
        start()
    }

    // Moving between the cover and main screens is a configuration change. The manifest routes it here instead of
    // recreating the screen, so the one recording keeps running; only the layout is rebuilt for the new display.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldPlot = plot
        val draft = typed.text.toString()
        val wasTyping = typed.hasFocus()
        bind()
        plot.takeOver(oldPlot)
        typed.setText(draft)
        if (draft.isNotEmpty() || wasTyping) typed.visibility = View.VISIBLE
    }

    private fun bind() {
        setContentView(R.layout.capture)
        status = findViewById(R.id.status)
        catalog = findViewById(R.id.catalog)
        strip = findViewById(R.id.strip)
        said = findViewById(R.id.said)
        typed = findViewById(R.id.typed)
        now = findViewById(R.id.now)
        plot = findViewById(R.id.plot)
        catalog.text = "Capture · %03d".format(number)
        strip.showStatus(status(micError))
        typed.setOnFocusChangeListener { _, focused ->
            show(if (focused) TYPING else listening())
            if (!focused && typed.text.isBlank()) typed.visibility = View.GONE
            render()
        }
        findViewById<View>(R.id.done).setOnClickListener {
            buzz(VibrationEffect.EFFECT_DOUBLE_CLICK)
            save(animate = true)
        }
        findViewById<View>(R.id.type).setOnClickListener { type() }
        findViewById<View>(R.id.cancel).setOnClickListener { cancel() }
        findViewById<View>(R.id.settings).setOnClickListener {
            save(animate = false)
            // own task: this screen's task is hidden from Recents, and Settings must be easy to return to from the browser
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // after a microphone problem the plot is the way back to listening
        plot.setOnClickListener { if (micError) start() }
        plot.isClickable = micError
        plot.listening = recorder != null
        show(message.ifEmpty { listening() })
        render()
    }

    private fun listening() = if (key == null) LISTENING_NO_KEY else LISTENING

    // the vibrator itself, not haptic feedback: the owner keeps system vibration off for typing but wants these felt
    private fun buzz(effect: Int) = getSystemService(VibratorManager::class.java).vibrate(
        CombinedVibration.createParallel(VibrationEffect.createPredefined(effect)),
        VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build(),
    )

    private fun start() {
        if (recorder != null) return
        if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingPermission = true
            requestPermissions(arrayOf(RECORD_AUDIO), 1)
            return
        }
        val r = Recorder(
            File(filesDir, "captures/${System.currentTimeMillis()}.pcm"),
            onLevel = { level -> runOnUiThread { plot.setLevel(level) } },
            onSentence = { pcm -> runOnUiThread { sentenceEnded(pcm) } },
            onMicLost = {
                runOnUiThread {
                    // what was recorded before the failure is still in the file, so Done keeps it
                    micError = true
                    plot.listening = false
                    strip.showStatus(status(micError = true))
                    show("The microphone stopped. Tap Done to keep what you said.")
                }
            },
        )
        try {
            r.start()
        } catch (e: Exception) {
            Log.e("Capture", "microphone", e)
            return micProblem("Couldn't open the microphone. Tap the lines to try again, or type.")
        }
        recorder = r
        buzz(VibrationEffect.EFFECT_HEAVY_CLICK) // it's listening, so a capture can start without looking
        micError = false
        plot.isClickable = false
        plot.listening = true
        strip.showStatus(status())
        show(listening())
        startRecognizer(r)
    }

    // Rough live words only: the recognizer listens to our recording through a pipe in one session that doesn't stop
    // at pauses. If it can't, captions wait for Gemini and nothing is lost, since the recording is the record.
    private fun startRecognizer(r: Recorder) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            it.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, r.recognizerInput)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        askingPermission = false
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else micProblem("No microphone permission. Type instead, or tap the lines to ask again.")
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
        typed.visibility = View.VISIBLE
        typed.requestFocus()
        typed.setSelection(typed.length())
        getSystemService(InputMethodManager::class.java).showSoftInput(typed, 0)
    }

    private fun show(msg: String) {
        message = msg
        status.text = listOfNotNull(notice, msg).joinToString("\n")
    }

    /** Exact sentences plain, rough ones (still waiting for Gemini) in italics; the words being spoken go large below. */
    private fun render() {
        val text = SpannableStringBuilder()
        for (s in sentences) {
            val words = s.exact ?: s.rough
            if (words.isBlank()) continue
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(words)
            if (s.exact == null) text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val max = if (said.maxLines <= 2) 90 else 260
        // cut the styled text itself, so sentences still waiting for Gemini stay italic in a long capture
        said.text = if (text.length > max) SpannableStringBuilder("…").append(text.subSequence(tailFrom(text, max), text.length)) else text
        said.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        // the prompt is gray text, not a hint: auto-sizing (cover screen) lays a hint out one letter per line, "S" / "t".
        // Saving reads roughWords, never this view, so the prompt can't end up in a capture.
        val live = roughWords.live
        val prompt = sentences.isEmpty() && live.isEmpty() && !typed.hasFocus()
        now.text = if (prompt) "Start talking" else live
        now.setTextColor(getColor(if (prompt) R.color.ink_muted else R.color.ink_line))
    }

    private fun sentenceEnded(pcm: ByteArray) {
        if (closing) return
        val s = Sentence(rough = roughWords.cut())
        sentences += s
        plot.freeze()
        render()
        val k = key ?: return
        corrector.execute {
            runCatching { geminiTranscribe(pcm, k) }
                .onSuccess { exact ->
                    runOnUiThread {
                        s.exact = exact
                        // only the latest sentence can have spilled late words onto the live line
                        if (s === sentences.last()) roughWords.corrected(exact)
                        render()
                    }
                }
                .onFailure { Log.w("Capture", "live correction failed: ${it.message}") } // rough stays; the job transcribes anyway
        }
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun onPartialResults(b: Bundle?) {
        roughWords.partial(best(b) ?: return)
        render()
    }

    override fun onSegmentResults(b: Bundle) {
        roughWords.segment(best(b) ?: "")
        render()
    }

    override fun onResults(b: Bundle?) = onSegmentResults(b ?: Bundle())

    override fun onEndOfSegmentedSession() {}

    override fun onError(error: Int) {
        // rough words stop here; the recording, the plot and the Gemini corrections carry on
        Log.w("Capture", "recognizer error $error; live rough words off for this capture")
        recognizer?.destroy()
        recognizer = null
    }

    private fun stopListening() {
        recognizer?.destroy()
        recognizer = null
        recorder?.stop()
        plot.listening = false
    }

    private fun cancel() {
        if (closing) return
        closing = true
        stopListening()
        recorder?.file?.delete()
        finish()
    }

    private fun save(animate: Boolean) {
        if (closing) return
        closing = true
        stopListening()
        // include words still being heard: tapping Done mid-sentence must not drop them
        val spoken = (sentences.map { it.exact ?: it.rough } + roughWords.live).filter(String::isNotBlank).joinToString(" ")
        val words = typed.text.toString().trim()
        val shown = listOf(spoken, words).filter(String::isNotBlank).joinToString("\n\n")
        // without a key the recording can't be transcribed, so only what was shown is worth sending
        val audio = recorder?.takeIf { it.heardSpeech && key != null }?.file
        if (audio == null) recorder?.file?.delete()
        if (shown.isEmpty() && audio == null) return finish()
        val queued = SaveJob.enqueue(this, shown, audio, words)
        if (!queued) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("capture", shown))
            Toast.makeText(applicationContext, "Couldn't queue it. Text copied to clipboard.", Toast.LENGTH_LONG).show()
        }
        if (queued && animate && ValueAnimator.areAnimatorsEnabled()) {
            catalog.text = "%03d filed".format(number)
            status.text = "Filed."
            now.text = ""
            plot.fold { finish() }
            return
        }
        if (queued) Toast.makeText(applicationContext, "Captured", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // pressed back or home, or the screen went off: keep what was said
        if (!isChangingConfigurations && !askingPermission) save(animate = false)
    }

    override fun onDestroy() {
        stopListening()
        corrector.shutdown()
        super.onDestroy()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
