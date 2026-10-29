package app.thoughtcapturer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TasksTest {
    @Test
    fun splitsGeminiJson() {
        val tasks = toTasks("""[{"title":"Email prof about lab","due":"2026-10-30"},{"title":" Buy groceries "}]""", "raw")
        assertEquals(listOf(Task("Email prof about lab", "2026-10-30"), Task("Buy groceries", null)), tasks)
    }

    @Test
    fun fallsBackToRawCapture() {
        for (bad in listOf(null, "", "not json", "[]", """[{"title":"  "}]""", """{"title":"x"}""")) {
            assertEquals(bad.toString(), listOf(Task("call mom", null)), toTasks(bad, "call mom"))
        }
    }

    @Test
    fun dropsBadDatesAndCapsTitles() {
        val task = toTasks("""[{"title":"${"a".repeat(300)}","due":"Friday"}]""", "raw").single()
        assertEquals(255, task.title.length)
        assertNull(task.due)
    }

    @Test
    fun dropsDatesThatDoNotExist() {
        assertNull(toTasks("""[{"title":"Pay rent","due":"2027-02-29"}]""", "raw").single().due)
    }

    @Test
    fun findsDefaultListOnALaterPage() {
        val pages = mapOf(
            TODO_LISTS to JSONObject("""{"value":[{"id":"cogs","wellknownListName":"none"}],"@odata.nextLink":"page2"}"""),
            "page2" to JSONObject("""{"value":[{"id":"tasks","wellknownListName":"defaultList"}]}"""),
        )
        assertEquals("tasks", defaultListId { pages.getValue(it) })
    }
}
