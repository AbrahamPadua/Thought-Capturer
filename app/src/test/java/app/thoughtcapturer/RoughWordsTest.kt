package app.thoughtcapturer

import org.junit.Assert.assertEquals
import org.junit.Test

class RoughWordsTest {
    @Test
    fun wordsGivenToASentenceAreNotShownAgain() {
        val r = RoughWords()
        r.partial("hello how are")
        assertEquals("hello how are", r.cut())
        r.partial("hello how are you doing") // same recognizer segment, still cumulative
        assertEquals("you doing", r.live)
    }

    @Test
    fun wordsFinishedAfterACutCarryIntoTheNextSentence() {
        val r = RoughWords()
        r.partial("a b c")
        r.cut()
        r.segment("a b c d") // the recognizer's segment ended later than our cut
        assertEquals("d", r.live)
        r.partial("e f")
        assertEquals("d e f", r.live)
        assertEquals("d e f", r.cut())
    }

    @Test
    fun lateWordsTheCorrectionAlreadyHoldsAreDropped() {
        val r = RoughWords()
        r.partial("this is a test to see")
        assertEquals("this is a test to see", r.cut())
        // the recognizer catches up after the cut, so the end of that sentence lands on the new line
        r.partial("this is a test to see if this works to know")
        assertEquals("if this works to know", r.live)
        r.corrected("This is a test to see if this works.")
        assertEquals("to know", r.live)
        r.partial("this is a test to see if this works to know more")
        assertEquals("to know more", r.live) // and it stays dropped as the partial grows
    }

    @Test
    fun repeatsInCarriedWordsAreDroppedToo() {
        val r = RoughWords()
        r.partial("x y")
        r.cut()
        r.segment("x y z w")
        r.partial("q")
        assertEquals("z w q", r.live)
        r.corrected("X y z w.")
        assertEquals("q", r.live)
    }

    @Test
    fun aSingleSharedWordIsNotTreatedAsARepeat() {
        val r = RoughWords()
        r.partial("go to the")
        r.cut()
        r.segment("go to the")
        r.partial("the store")
        r.corrected("Go to the.")
        assertEquals("the store", r.live)
    }
}
