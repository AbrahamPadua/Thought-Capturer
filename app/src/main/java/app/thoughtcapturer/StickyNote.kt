package app.thoughtcapturer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject

/**
 * One item on the sticky note: a Later line, or a note's title and points. [app] and [web] link to the OneNote page it
 * came from. A capture's items share its job id, so a retried upload replaces them instead of adding copies.
 */
data class Sticky(val id: String, val later: Boolean, val title: String, val points: List<String>, val at: Long, val app: String?, val web: String?)

/** The sticky note's items for a filed note. [links] is the "links" object of OneNote's reply to the new page. */
internal fun stickiesFor(id: String, note: Note, at: Long, links: JSONObject?): List<Sticky> {
    fun link(name: String) = links?.optJSONObject(name)?.optString("href")?.ifEmpty { null }
    val app = link("oneNoteClientUrl")
    val web = link("oneNoteWebUrl")
    return note.later.mapIndexed { i, s -> Sticky("$id.$i", true, s, emptyList(), at, app, web) } +
        listOfNotNull(Sticky(id, false, note.title, note.points, at, app, web).takeIf { note.points.isNotEmpty() })
}

internal fun stickies(json: String?): List<Sticky> = runCatching {
    val a = JSONArray(json ?: "[]")
    (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        val p = o.optJSONArray("points") ?: JSONArray()
        Sticky(
            o.getString("id"), o.optBoolean("later"), o.optString("title"), (0 until p.length()).map(p::getString),
            o.optLong("at"), o.optString("app").ifEmpty { null }, o.optString("web").ifEmpty { null },
        )
    }
}.getOrDefault(emptyList())

internal fun List<Sticky>.json(): String = JSONArray(map {
    JSONObject().put("id", it.id).put("later", it.later).put("title", it.title).put("points", JSONArray(it.points))
        .put("at", it.at).put("app", it.app).put("web", it.web)
}).toString()

/** Later lines on top, then notes, each newest first; an id already there is replaced. */
internal fun pinned(json: String?, add: List<Sticky>): List<Sticky> {
    val ids = add.map { it.id }.toSet()
    val all = add + stickies(json).filter { it.id !in ids }
    // a short note, not an archive: the newest 30 of each kind
    return all.filter { it.later }.sortedByDescending { it.at }.take(30) + all.filter { !it.later }.sortedByDescending { it.at }.take(30)
}

private val lock = Any() // upload jobs run on their own threads, and a check-off can land at the same time

private fun Context.restick(change: (String?) -> List<Sticky>) {
    synchronized(lock) { prefs().run { edit().putString("sticky", change(getString("sticky", null)).json()).commit() } }
    StickyWidget.refresh(this)
}

fun Context.stick(add: List<Sticky>) = restick { pinned(it, add) }

fun Context.unstick(id: String) = restick { json -> stickies(json).filter { it.id != id } }

/** The sticky note widget, for the home screen and the cover screen. Tap an item to open its page; tap its check to clear it. */
class StickyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = manager.updateAppWidget(ids, views(ctx))

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TAP) return super.onReceive(ctx, intent)
        val id = intent.getStringExtra("id") ?: return
        Log.i("Capture", "sticky note tap: $id clear=${intent.getBooleanExtra("clear", false)}")
        if (intent.getBooleanExtra("clear", false)) return ctx.unstick(id)
        val s = stickies(ctx.prefs().getString("sticky", null)).firstOrNull { it.id == id } ?: return
        // the OneNote app if it's installed, else the page in a browser
        for (url in listOfNotNull(s.app, s.web)) {
            try {
                return ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.i("Capture", "nothing opens ${url.substringBefore(':')} links")
            }
        }
    }

    companion object {
        private const val TAP = "app.thoughtcapturer.STICKY_TAP"

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, StickyWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2)
            var section: Boolean? = null
            var n = 0L
            for (s in stickies(ctx.prefs().getString("sticky", null))) {
                if (s.later != section) {
                    section = s.later
                    items.addItem(n++, RemoteViews(ctx.packageName, R.layout.sticky_header).apply {
                        setTextViewText(R.id.label, if (s.later) "Later" else "Notes")
                        setInt(R.id.block, "setBackgroundColor", ctx.getColor(if (s.later) R.color.code_todo else R.color.code_note))
                    })
                }
                items.addItem(n++, RemoteViews(ctx.packageName, R.layout.sticky_row).apply {
                    setTextViewText(R.id.title, s.title)
                    setTextViewText(R.id.points, s.points.joinToString("\n") { "· $it" })
                    setViewVisibility(R.id.points, if (s.points.isEmpty()) View.GONE else View.VISIBLE)
                    setOnClickFillInIntent(R.id.row, Intent().putExtra("id", s.id))
                    setOnClickFillInIntent(R.id.clear, Intent().putExtra("id", s.id).putExtra("clear", true))
                    setContentDescription(R.id.clear, "Clear ${s.title}")
                })
            }
            return RemoteViews(ctx.packageName, R.layout.sticky_widget).apply {
                setRemoteAdapter(R.id.list, items.build())
                setEmptyView(R.id.list, R.id.empty)
                // mutable so each item fills in its own id; explicit, so nothing else can receive it
                val tap = Intent(ctx, StickyWidget::class.java).setAction(TAP)
                setPendingIntentTemplate(R.id.list, PendingIntent.getBroadcast(ctx, 0, tap, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
    }
}
