package app.thoughtcapturer

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
}
