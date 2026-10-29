package app.thoughtcapturer

import android.app.job.JobScheduler
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** What the code strip says, read left to right: a mic problem, the last capture's to-dos and note, then the queue. */
data class Status(val micError: Boolean, val todos: Int, val notes: Int, val waiting: Int, val syncError: Boolean)

fun Context.status(micError: Boolean = false): Status {
    val p = prefs()
    // "<to-dos> <notes>", written by SaveJob after an upload lands
    val filed = p.getString("last_filed", null)?.split(" ")?.map { it.toIntOrNull() ?: 0 }.orEmpty()
    val waiting = getSystemService(JobScheduler::class.java).allPendingJobs.size
    return Status(micError, filed.getOrElse(0) { 0 }, filed.getOrElse(1) { 0 }, waiting, waiting > 0 && p.getString("last_error", null) != null)
}

internal fun Status.codes(): List<Int> = buildList {
    if (micError) add(R.color.code_error)
    repeat(todos.coerceAtMost(4)) { add(R.color.code_todo) }
    repeat(notes.coerceAtMost(1)) { add(R.color.code_note) }
    if (syncError) add(R.color.code_error)
    repeat(waiting.coerceAtMost(3)) { add(R.color.code_wait) }
}

internal fun Status.describe(): String = listOfNotNull(
    "Microphone problem".takeIf { micError },
    "Last capture filed $todos to-do${if (todos == 1) "" else "s"}${if (notes > 0) " and a note" else ""}".takeIf { todos + notes > 0 },
    "$waiting waiting to sync".takeIf { waiting > 0 },
    "Sync error".takeIf { syncError },
).joinToString(". ")

fun LinearLayout.showStatus(s: Status) {
    removeAllViews()
    val dp = resources.displayMetrics.density
    for (color in s.codes()) addView(View(context).apply {
        setBackgroundColor(context.getColor(color))
        layoutParams = LinearLayout.LayoutParams((18 * dp).toInt(), (6 * dp).toInt()).apply { marginEnd = (4 * dp).toInt() }
    })
    contentDescription = s.describe()
    visibility = if (childCount == 0) View.GONE else View.VISIBLE // an empty strip would leave a gap under its label
}
