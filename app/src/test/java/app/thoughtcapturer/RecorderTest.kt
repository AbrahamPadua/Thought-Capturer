package app.thoughtcapturer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    @Test
    fun loudnessMapsQuietRoomToZeroAndCloseSpeechToOne() {
        assertEquals(0f, levelOf(ShortArray(800), 800), 0f) // digital silence
        assertEquals(1f, levelOf(ShortArray(800) { 30000 }, 800), 0f) // near full scale
        val talking = levelOf(ShortArray(800) { if (it % 2 == 0) 1600 else -1600 }, 800) // about -26 dBFS
        assertTrue(talking in 0.6f..0.8f)
        assertEquals(0f, levelOf(ShortArray(0), 0), 0f)
    }

    @Test
    fun cutsASentenceAfterAPauseButNotAfterACough() {
        val s = Segmenter(threshold = 0.25f, pauseMs = 700, minSpeechMs = 300)
        // a 100ms cough then silence: held, no cut
        assertFalse(s.feed(0.9f, 100))
        assertFalse((1..20).any { s.feed(0f, 50) })
        // a second of speech, then 650ms of quiet is not yet a pause...
        assertFalse((1..20).any { s.feed(0.6f, 50) })
        assertFalse((1..13).any { s.feed(0.1f, 50) })
        // ...the 700th millisecond is
        assertTrue(s.feed(0.1f, 50))
        // and silence after the cut cuts nothing more
        assertFalse((1..40).any { s.feed(0f, 50) })
    }

    @Test
    fun wavHeaderDescribes16kMono16Bit() {
        val pcm = ByteArray(3200) { it.toByte() }
        val w = wav(pcm)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(44 + pcm.size, w.size)
    }

    @Test
    fun longTranscriptsKeepTheNewestWords() {
        assertEquals("short", tail("short", 90))
        val t = tail("one two three four five six seven", 15)
        assertTrue(t, t.startsWith("…") && t.endsWith("six seven"))
        assertFalse(t.contains("thre")) // cut at a word, never mid-word
    }

    @Test
    fun readsTheTranscriptGeminiReturnsWithASplit() {
        assertEquals("buy rice, tapos mag-aral", transcriptOf("""{"transcript":" buy rice, tapos mag-aral ","tasks":[]}"""))
        assertNull(transcriptOf("""{"tasks":[]}"""))
        assertNull(transcriptOf(""))
        assertNull(transcriptOf(null))
    }
}
