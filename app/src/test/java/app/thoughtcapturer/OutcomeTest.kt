package app.thoughtcapturer

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {
    private val heard = """{"transcript":"Bumili ng bigas, then email the professor.","tasks":[{"title":"Email the professor"}]}"""

    @Test
    fun typedCaptureKeepsTheExactWordsEvenIfGeminiRewritesThem() {
        val rewrite = """{"transcript":"Call Mom regarding the rent.","tasks":[{"title":"Call mom"}]}"""
        assertEquals(Outcome.Save("call mom re: rent"), outcome(false, rewrite, "call mom re: rent", "call mom re: rent", 0, true))
    }

    @Test
    fun recordingIsFiledWithItsTranscriptAndTypedWords() {
        assertEquals(
            Outcome.Save("Bumili ng bigas, then email the professor.\n\nroom 204"),
            outcome(true, heard, "bumili bigas then email", "room 204", 0, true),
        )
    }

    @Test
    fun failedTranscriptionIsRetriedBeforeFallingBackToRoughWords() {
        assertEquals(Outcome.Retry, outcome(true, "", "rough words", "", 0, true))
        assertEquals(Outcome.Retry, outcome(true, "", "rough words", "", MAX_TRIES - 1, true))
        assertEquals(Outcome.Save("rough words"), outcome(true, "", "rough words", "", MAX_TRIES, true))
    }

    @Test
    fun recordingWithNothingOnScreenIsNeverDroppedForAFailedCall() {
        assertEquals(Outcome.Retry, outcome(true, "", "", "", 99, true))
        assertEquals(Outcome.Retry, outcome(true, "", "", "", 0, false)) // key removed: waits until there is one
    }

    @Test
    fun withoutAKeyTheRoughWordsAreFiledAtOnce() {
        assertEquals(Outcome.Save("rough words"), outcome(true, "", "rough words", "", 0, false))
    }

    @Test
    fun onlyNoiseAndNothingTypedIsDropped() {
        assertEquals(Outcome.Drop, outcome(true, """{"transcript":"","tasks":[]}""", "", "", 0, true))
    }

    @Test
    fun failedSplitOfTypedTextIsStillFiledUnsplit() {
        assertEquals(Outcome.Save("buy rice"), outcome(false, "", "buy rice", "buy rice", 0, true))
    }

    @Test
    fun throttlingAndServerErrorsAreRetriedInPlaceButRejectionsAreNot() {
        assertTrue(passing(HttpError(429, "")))
        assertTrue(passing(HttpError(503, "")))
        assertTrue(passing(SocketTimeoutException()))
        assertFalse(passing(HttpError(400, ""))) // a bad request fails the same way 30 s later
        assertFalse(passing(IOException("Not signed in to Microsoft. Open Settings to sign in.")))
    }

    @Test
    fun oversizedAudioReadsFirstCapBytesInsteadOfDroppingFile() {
        val f = java.io.File.createTempFile("capture", ".pcm")
        try {
            f.writeBytes(ByteArray(100) { it.toByte() })
            val capped = readAudioCap(f, cap = 40)!!
            assertEquals(40, capped.size)
            assertEquals(39.toByte(), capped.last())
        } finally {
            f.delete()
        }
    }
}
