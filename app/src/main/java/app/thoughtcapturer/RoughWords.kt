package app.thoughtcapturer

private fun words(s: String) = s.split(' ').filter { it.isNotBlank() }

private fun join(a: List<String>, b: List<String>) = (a + b).joinToString(" ")

/**
 * Which of the recognizer's rough words belong to the sentence being spoken. The recognizer sends cumulative
 * partials within its own segments and runs about half a second behind the audio, while sentences are cut from the
 * audio at pauses. So words said just before a cut can arrive after it and land on the next line;
 * [corrected] drops them once Gemini's exact version of that sentence, which already holds them, comes back.
 */
internal class RoughWords {
    private var carry = emptyList<String>() // from finished recognizer segments, still in the current sentence
    private var raw = emptyList<String>() // the recognizer's latest partial in its current segment
    private var used = 0 // how many of those partial words earlier sentences already took

    /** The rough words of the sentence being spoken. */
    val live get() = join(carry, raw.drop(used))

    fun partial(text: String) {
        raw = words(text)
    }

    fun segment(text: String) {
        carry = carry + words(text).drop(used)
        raw = emptyList()
        used = 0
    }

    /** A sentence was cut from the audio: hands back its rough words and starts the next one. */
    fun cut(): String {
        val sentence = live
        carry = emptyList()
        used = raw.size
        return sentence
    }

    /**
     * Gemini's exact words for the latest sentence arrived: drop the run of words that starts the live line and ends
     * [exact]. Two words at least, so a single common word ("the") never counts as a repeat.
     */
    fun corrected(exact: String) {
        fun norm(w: String) = w.lowercase().filter(Char::isLetterOrDigit)
        val head = words(live).map(::norm)
        val tail = words(exact).map(::norm)
        val n = (minOf(head.size, tail.size) downTo 2).firstOrNull { head.take(it) == tail.takeLast(it) } ?: return
        val fromCarry = minOf(n, carry.size)
        carry = carry.drop(fromCarry)
        used += n - fromCarry
    }
}
