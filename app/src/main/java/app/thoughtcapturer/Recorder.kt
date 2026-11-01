package app.thoughtcapturer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

const val RATE = 16000
private const val CHUNK = RATE / 20 // 50ms of samples

/** Loudness of one chunk on the plot's 0..1 scale: about -55 dBFS (a quiet room) is 0, -15 dBFS (close speech) is 1. */
internal fun levelOf(samples: ShortArray, count: Int): Float {
    if (count == 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    val dbfs = 20 * log10(sqrt(sum / count) / 32768 + 1e-9)
    return ((dbfs + 55) / 40).toFloat().coerceIn(0f, 1f)
}

/** Loudness that counts as speech: it starts sentences, keeps the recording for Gemini, and turns the plot's line solid. */
internal const val SPEECH = 0.25f

/** Cuts speech into sentences at pauses: a cut comes after [pauseMs] of quiet that follows at least [minSpeechMs] of speech. */
internal class Segmenter(private val threshold: Float = SPEECH, private val pauseMs: Long = 700, private val minSpeechMs: Long = 300) {
    private var speechMs = 0L
    private var quietMs = 0L

    /** Feed one chunk; true when a sentence just ended. A cough-length blip is held and joins the next sentence. */
    fun feed(level: Float, chunkMs: Long): Boolean {
        if (level >= threshold) {
            speechMs += chunkMs
            quietMs = 0
            return false
        }
        if (speechMs == 0L) return false
        quietMs += chunkMs
        if (quietMs < pauseMs) return false
        quietMs = 0
        if (speechMs < minSpeechMs) return false
        speechMs = 0
        return true
    }

    val speaking get() = speechMs > 0
}

/**
 * Leaves long silences out of what goes to Gemini, so a capture left open on the desk stays small enough to send.
 * Keeps the first [keepMs] of every pause, so speech keeps its rhythm, and the last [leadMs] before speech comes back,
 * so a soft first word isn't clipped. [threshold] sits below the segmenter's, so quiet speech still counts as speech.
 */
internal class Trimmer(private val threshold: Float = 0.12f, private val keepMs: Long = 2000, private val leadMs: Long = 300) {
    private var quietMs = 0L
    private val held = ArrayDeque<Pair<ByteArray, Long>>()
    private var heldMs = 0L

    /** The chunks to keep now, given this one: none in the middle of a long pause. */
    fun feed(chunk: ByteArray, level: Float, chunkMs: Long): List<ByteArray> {
        if (level >= threshold) {
            quietMs = 0
            val out = held.map { it.first } + chunk
            held.clear()
            heldMs = 0
            return out
        }
        quietMs += chunkMs
        if (quietMs <= keepMs) return listOf(chunk)
        held.addLast(chunk to chunkMs)
        heldMs += chunkMs
        while (heldMs > leadMs) heldMs -= held.removeFirst().second
        return emptyList()
    }
}

/** 16kHz mono 16-bit PCM wrapped in a WAV header, the form Gemini takes inline. */
internal fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
    val out = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return out.array()
}

/**
 * One continuous recording from open to close, never restarted, so nothing said between sentences is lost.
 * Each 50ms chunk goes to the plot, to Android's recognizer through a pipe (rough live words), and to the segmenter.
 * With long silences trimmed out, it also goes to the file (the upload job's source of truth) and to the current
 * sentence, whose audio [onSentence] gets for a Gemini correction once the segmenter ends it. Callbacks run on the
 * recording thread.
 */
class Recorder(
    val file: File,
    private val onLevel: (Float) -> Unit,
    private val onSentence: (ByteArray) -> Unit,
    private val onMicLost: () -> Unit,
) {
    private val pipe = ParcelFileDescriptor.createPipe()

    /** The read end for SpeechRecognizer's EXTRA_AUDIO_SOURCE; the recognizer closes it. */
    val recognizerInput: ParcelFileDescriptor = pipe[0]

    // the recognizer may read slowly or stop; this queue drops for it rather than ever stalling the recording
    private val toRecognizer = ArrayBlockingQueue<ByteArray>(100)
    @Volatile private var running = false
    @Volatile var heardSpeech = false
        private set
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // the capture screen asks for RECORD_AUDIO before starting
    fun start() {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        running = true
        rec.startRecording()
        file.parentFile?.mkdirs()
        worker = thread(name = "recorder") {
            val samples = ShortArray(CHUNK)
            val bytes = ByteArray(CHUNK * 2)
            val sentence = ByteArrayOutputStream()
            val segmenter = Segmenter()
            val trimmer = Trimmer()
            FileOutputStream(file, true).buffered().use { out ->
                while (running) {
                    val n = rec.read(samples, 0, CHUNK)
                    if (n < 0) { // an error code (say the audio service restarted): every later read fails too, so stop instead of spinning
                        Log.e("Capture", "microphone read failed: $n")
                        running = false
                        onMicLost()
                        break
                    }
                    if (n == 0) continue
                    for (i in 0 until n) {
                        bytes[2 * i] = samples[i].toByte()
                        bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
                    }
                    val chunk = bytes.copyOf(n * 2) // its own copy: the trimmer may hold it and the recognizer queue keeps it
                    val level = levelOf(samples, n)
                    val ms = n * 1000L / RATE
                    for (c in trimmer.feed(chunk, level, ms)) {
                        out.write(c)
                        sentence.write(c)
                    }
                    toRecognizer.offer(chunk) // the recognizer hears everything, in real time
                    onLevel(level)
                    if (segmenter.feed(level, ms)) {
                        heardSpeech = true
                        onSentence(sentence.toByteArray())
                        sentence.reset()
                    } else if (segmenter.speaking) heardSpeech = true
                }
            }
            rec.stop()
            rec.release()
        }
        thread(name = "recognizer-feed") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    while (running || toRecognizer.isNotEmpty()) toRecognizer.poll(100, TimeUnit.MILLISECONDS)?.let(out::write)
                }
            } catch (e: IOException) {
                Log.i("Capture", "recognizer stopped reading: ${e.message}") // rough words end; the recording goes on
            }
        }
    }

    /** Stops and waits for the file to be complete. Safe to call twice. */
    fun stop() {
        running = false
        worker?.join(2000)
    }
}
