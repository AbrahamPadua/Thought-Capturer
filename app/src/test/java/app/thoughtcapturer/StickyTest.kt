package app.thoughtcapturer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StickyTest {
    private fun later(id: String, at: Long) = Sticky(id, true, "maybe $id", emptyList(), at, null, null)
    private fun note(id: String, at: Long) = Sticky(id, false, "note $id", listOf("a point"), at, "onenote:x", "https://x")

    @Test
    fun aFiledNoteBecomesItsLaterLinesAndItsPoints() {
        val links = JSONObject("""{"oneNoteClientUrl":{"href":"onenote:https://p"},"oneNoteWebUrl":{"href":"https://p"}}""")
        val items = stickiesFor("job7", Note("Vest", listOf("32 motors"), listOf("Maybe ask Dr. K", "Look into EEG")), 5, links)
        assertEquals(
            listOf(
                Sticky("job7.0", true, "Maybe ask Dr. K", emptyList(), 5, "onenote:https://p", "https://p"),
                Sticky("job7.1", true, "Look into EEG", emptyList(), 5, "onenote:https://p", "https://p"),
                Sticky("job7", false, "Vest", listOf("32 motors"), 5, "onenote:https://p", "https://p"),
            ),
            items,
        )
        // a Later-only page adds no empty note, and a reply without links still pins the lines
        assertEquals(listOf(later("job8.0", 6).copy(title = "x")), stickiesFor("job8", Note("Later", emptyList(), listOf("x")), 6, null))
    }

    @Test
    fun laterLinesComeFirstThenNotesEachNewestFirst() {
        val json = pinned(null, listOf(note("n1", 1), later("l1", 1))).json()
        val all = pinned(json, listOf(note("n2", 2), later("l2", 2)))
        assertEquals(listOf("l2", "l1", "n2", "n1"), all.map { it.id })
        assertEquals(all, stickies(all.json())) // survives being stored
    }

    @Test
    fun aRetriedUploadReplacesItsItemsInsteadOfAddingCopies() {
        val json = pinned(null, listOf(later("job3.0", 1))).json()
        assertEquals(listOf(later("job3.0", 2)), pinned(json, listOf(later("job3.0", 2))))
    }

    @Test
    fun keepsTheNewestThirtyOfEachKind() {
        val all = pinned(null, (1..40L).map { later("l$it", it) } + (1..40L).map { note("n$it", it) })
        assertEquals(60, all.size)
        assertEquals("l40", all.first().id)
        assertEquals("n11", all.last().id)
    }

    @Test
    fun aBrokenStoreReadsAsEmpty() {
        assertEquals(emptyList<Sticky>(), stickies("not json"))
        assertEquals(emptyList<Sticky>(), stickies(null))
    }
}
