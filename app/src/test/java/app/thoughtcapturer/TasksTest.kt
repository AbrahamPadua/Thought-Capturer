package app.thoughtcapturer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {
    @Test
    fun splitsTasksAndNote() {
        val split = parseSplit(
            """{"tasks":[{"title":"Email prof about lab","due":"2026-10-30"},{"title":" Buy groceries "}],""" +
                """"note":{"title":"Haptic vest","points":["32 motors"," 40 Hz stimulation ",""]}}""",
            "raw",
        )
        assertEquals(listOf(Task("Email prof about lab", "2026-10-30"), Task("Buy groceries", null)), split.tasks)
        assertEquals(Note("Haptic vest", listOf("32 motors", "40 Hz stimulation")), split.note)
    }

    @Test
    fun noteOnlyCaptureMakesNoTask() {
        val split = parseSplit("""{"tasks":[],"note":{"title":"Vest idea","points":["Use 32 motors"]}}""", "raw")
        assertEquals(emptyList<Task>(), split.tasks)
        assertEquals(Note("Vest idea", listOf("Use 32 motors")), split.note)
    }

    @Test
    fun fallsBackToRawCapture() {
        val bad = listOf(
            null, "", "not json", "{}", "[]", // "[]": a split cached in the old array format before an update
            """{"tasks":[{"title":"  "}]}""",
            """{"tasks":[],"note":{"title":"x","points":[]}}""",
        )
        for (json in bad) assertEquals(json.toString(), Split(listOf(Task("call mom", null)), null), parseSplit(json, "call mom"))
    }

    @Test
    fun dropsBadDatesAndCapsTitles() {
        val task = parseSplit("""{"tasks":[{"title":"${"a".repeat(300)}","due":"Friday"}]}""", "raw").tasks.single()
        assertEquals(255, task.title.length)
        assertNull(task.due)
    }

    @Test
    fun dropsDatesThatDoNotExist() {
        assertNull(parseSplit("""{"tasks":[{"title":"Pay rent","due":"2027-02-29"}]}""", "raw").tasks.single().due)
    }

    @Test
    fun keepsAReminderTimeAndImportanceAndDropsBadTimes() {
        val tasks = parseSplit(
            """{"tasks":[{"title":"Email prof","remind":"2026-10-31T16:00","important":true},{"title":"Call mom","remind":"4pm"}]}""",
            "raw",
        ).tasks
        assertEquals(Task("Email prof", null, "2026-10-31T16:00:00", true), tasks[0])
        assertEquals(Task("Call mom", null), tasks[1])
    }

    @Test
    fun taskJsonTurnsOnTheReminderAndStars() {
        val t = taskJson(Task("Email prof", "2026-11-02", "2026-10-31T16:00:00", true), "email prof at 4, urgent", "Pacific Standard Time")
        assertTrue(t.getBoolean("isReminderOn"))
        assertEquals("2026-10-31T16:00:00", t.getJSONObject("reminderDateTime").getString("dateTime"))
        assertEquals("Pacific Standard Time", t.getJSONObject("reminderDateTime").getString("timeZone"))
        assertEquals("high", t.getString("importance"))
        assertEquals("2026-11-02T00:00:00", t.getJSONObject("dueDateTime").getString("dateTime"))
        val plain = taskJson(Task("Buy rice", null), "Buy rice", "UTC")
        assertEquals("""{"title":"Buy rice"}""", plain.toString()) // nothing To Do would read as a reminder, a star or a duplicate body
    }

    @Test
    fun keepsStepsAndParksMaybesInTheNote() {
        val split = parseSplit(
            """{"tasks":[{"title":"Write lit review","steps":["Check outcome measures"," ","Reorganize sources"]}],""" +
                """"later":["Maybe ask Dr. K about EEG"]}""",
            "raw",
        )
        assertEquals(listOf("Check outcome measures", "Reorganize sources"), split.tasks.single().steps)
        assertEquals(Note("Later", emptyList(), listOf("Maybe ask Dr. K about EEG")), split.note) // a page even with no other note
        val html = noteHtml(split.note!!, "raw", 0)
        assertTrue(html, "<p><b>Later</b></p><ul><li>Maybe ask Dr. K about EEG</li></ul>" in html)
        assertFalse(html, "<ul></ul>" in html)
    }

    @Test
    fun uploadsEachTaskThenItsStepsThenThePage() {
        val split = Split(listOf(Task("a", null, steps = listOf("a1", "a2")), Task("b", null)), Note("n", listOf("x")))
        assertEquals(listOf(Upload.ToDo(0), Upload.Step(0, 0), Upload.Step(0, 1), Upload.ToDo(1), Upload.Page), uploads(split))
        assertEquals(listOf(Upload.ToDo(0)), uploads(Split(listOf(Task("a", null)), null)))
    }

    @Test
    fun noteHtmlEscapesSpeech() {
        val html = noteHtml(Note("a < b", listOf("x & y")), "say \"hi\" <3", 0)
        assertTrue(html, "<title>a &lt; b</title>" in html)
        assertTrue(html, "<li>x &amp; y</li>" in html)
        assertTrue(html, "say &quot;hi&quot; &lt;3" in html)
        assertFalse(html, "<3" in html)
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
