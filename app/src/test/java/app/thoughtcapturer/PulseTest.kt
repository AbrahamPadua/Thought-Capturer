package app.thoughtcapturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseTest {
    @Test
    fun mapsRecognizerDecibelsToALevel() {
        assertEquals(0f, levelFromRms(-5f), 0f)
        assertEquals(0f, levelFromRms(-2f), 0f)
        assertEquals(0.5f, levelFromRms(4f), 1e-6f)
        assertEquals(1f, levelFromRms(30f), 0f)
    }

    @Test
    fun stepsLevelsWhenAnimationsAreOff() {
        assertEquals(0.5f, stepped(0.49f, 5), 0f)
        assertEquals(0.25f, stepped(0.2f, 5), 0f)
        assertEquals(1f, stepped(0.95f, 5), 0f)
    }

    @Test
    fun fitsTwelveLinesOnTheMainScreenAndFewerOnTheCover() {
        assertEquals(12, lineCountFor(240f, 12f, 12)) // main screen plot band
        assertEquals(6, lineCountFor(110f, 12f, 12)) // cover screen band
        assertEquals(1, lineCountFor(20f, 12f, 12)) // keyboard up: only the live line
    }

    @Test
    fun ridgeRisesInTheMiddleAndStaysFlatAtTheEdges() {
        val r = ridge(1f, seed = 3, phase = 0f)
        assertTrue(r.first() < 0.01f && r.last() < 0.01f)
        assertTrue(r.max() > 0.5f)
        assertTrue(ridge(0f, 3, 0f).all { it == 0f })
    }

    @Test
    fun frozenLineFollowsTheSentencesOwnLoudness() {
        assertEquals(null, voiceRidge(listOf(0.5f, 0.6f))) // too few readings: caller falls back to a plain ridge
        val rising = voiceRidge(List(20) { it / 19f })!!
        assertEquals(0f, rising.first(), 0f)
        assertEquals(0f, rising.last(), 0f)
        // loudness rises through the sentence, so the line is higher past the middle than before it
        assertTrue(rising[34] > rising[20])
        // it leaves the baseline gently: the first raised point is still close to flat
        val firstRaised = rising.first { it > 0f }
        assertTrue(firstRaised < 0.05f)
        val whisper = voiceRidge(List(10) { 0.05f })!!
        assertTrue(whisper.max() in 0.8f..FROZEN_PEAK) // every sentence is drawn at full height, whisper or shout
    }

    @Test
    fun stackDropsTheOldestLineAtTheCap() {
        val stack = mutableListOf<Int>()
        (1..13).forEach { stack.pushCapped(it, 11) }
        assertEquals((3..13).toList(), stack)
    }

    @Test
    fun codeStripReadsMicThenFiledThenQueue() {
        val s = Status(micError = true, todos = 6, notes = 1, waiting = 5, syncError = true)
        assertEquals(
            listOf(R.color.code_error) + List(4) { R.color.code_todo } + R.color.code_note + R.color.code_error + List(3) { R.color.code_wait },
            s.codes(),
        )
        assertEquals("Last capture filed 1 to-do and a note", Status(false, 1, 1, 0, false).describe())
        assertEquals(emptyList<Int>(), Status(false, 0, 0, 0, false).codes())
    }
}
