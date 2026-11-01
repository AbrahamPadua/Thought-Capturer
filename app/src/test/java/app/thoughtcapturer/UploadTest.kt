package app.thoughtcapturer

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric needs Java 21 for SDK 35+, and this PC builds with JDK 17
class UploadTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val split = Split(listOf(Task("Write lit review", null, steps = listOf("Check measures"))), Note("Vest", listOf("32 motors"), listOf("Maybe ask Dr K")))
    private val step = "$TODO_LISTS/L/tasks/T1/checklistItems"
    private val calls = mutableListOf<String>()

    /** Graph as the job sees it, failing once at [failAt]. */
    private fun graph(failAt: String?) = { url: String, _: String?, _: String ->
        calls += url
        if (url == failAt) throw IOException("connection dropped")
        when {
            url == TODO_LISTS -> JSONObject("""{"value":[{"id":"L","wellknownListName":"defaultList"}]}""")
            url.endsWith("/tasks") -> JSONObject("""{"id":"T1"}""")
            else -> JSONObject("{}")
        }
    }

    @Test
    fun aRetryResumesAtTheFailedCallAndHangsTheStepOnTheTaskAlreadyFiled() {
        assertThrows(IOException::class.java) { ctx.fileUploads("job9", split, "raw", 0, "UTC", graph(failAt = step)) }
        calls.clear()
        ctx.fileUploads("job9", split, "raw", 0, "UTC", graph(failAt = null))
        assertEquals(listOf(TODO_LISTS, step, ONENOTE_PAGES), calls) // the task itself is not posted a second time
        assertEquals("Maybe ask Dr K", stickies(ctx.prefs().getString("sticky", null)).first().title)
    }
}
