package app.thoughtcapturer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val RIDGE = 3.2f // tallest ridge, in line spacings: tall enough to cut into the lines behind
private const val POINTS = 56

/** Snaps to one of [steps] levels; used when animations are off, so the line jumps between clear heights. */
internal fun stepped(level: Float, steps: Int) = (level * (steps - 1)).roundToInt().toFloat() / (steps - 1)

/** Lines that fit: one every [spacing], with room above the top one for the tallest ridge. */
internal fun lineCountFor(height: Float, spacing: Float, maxLines: Int) =
    (floor((height - RIDGE * spacing) / spacing).toInt() + 1).coerceIn(1, maxLines)

/** Flat at the edges, rising in the middle, like the sleeve. */
internal fun envelope(x: Float): Float {
    val d = (x - 0.5f) / 0.17f
    return exp(-d * d)
}

/** A broad swell with a finer jag riding on it, so peaks read as a voice rather than a comb. Values stay in 0..level. */
internal fun ridge(level: Float, seed: Int, phase: Float) = FloatArray(POINTS) { i ->
    val swell = 0.3f * sin(i * 0.55f + seed * 1.3f + phase * 1.1f)
    val jag = 0.2f * sin(i * 1.7f + seed * 0.4f + phase * 2.3f)
    level * envelope(i / (POINTS - 1f)) * (0.5f + swell + jag)
}

/**
 * A finished sentence's own loudness over time, laid across the middle of the line and tapered smoothly to
 * the baseline at both ends, so each frozen line is that sentence. Every sentence is drawn at full height
 * (the shape is the sentence; how loud it was is not), so the stack fills its band. Null under 3 readings.
 */
internal fun voiceRidge(levels: List<Float>): FloatArray? {
    if (levels.size < 3) return null
    val peak = levels.max()
    val gain = if (peak > 0f) FROZEN_PEAK / peak else 0f
    return FloatArray(POINTS) { i ->
        val t = (i / (POINTS - 1f) - 0.12f) / 0.76f
        if (t <= 0f || t >= 1f) return@FloatArray 0f
        val pos = t * (levels.size - 1)
        val lo = pos.toInt()
        val sample = levels[lo] + (levels[minOf(lo + 1, levels.size - 1)] - levels[lo]) * (pos - lo)
        // exponent above 1: the line leaves the baseline gently instead of bending at a corner
        sample * gain * Math.pow(sin(Math.PI * t), 1.6).toFloat()
    }
}

internal const val FROZEN_PEAK = 0.9f
private const val LIVE_GAIN = 1.8f // the synthetic live ridge peaks near half its level; this matches it to frozen lines

internal fun <T> MutableList<T>.pushCapped(item: T, cap: Int) {
    add(item)
    while (size > cap) removeAt(0)
}

/**
 * The capture screen's signature: a stacked pulse plot. The front line follows your voice; each finished
 * sentence freezes it into the stack, so the plot shows both that you're heard and how much you've said.
 */
class PulsePlotView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val minSpacing = 12 * dp
    private val ground = context.getColor(R.color.ink_ground)
    private val frozenPaint = stroke(R.color.ink_line, 1.5f)
    private val emptyPaint = stroke(R.color.ink_faint, 1f)
    private val livePaint = stroke(R.color.ink_line, 3f)
    private val quietDash = DashPathEffect(floatArrayOf(6 * dp, 6 * dp), 0f)
    private val fill = Paint().apply { color = ground; style = Paint.Style.FILL }
    private val path = Path()

    private val frozen = mutableListOf<FloatArray>()
    private val segment = mutableListOf<Float>() // this sentence's levels so far, about 20 a second
    private var target = 0f
    private var shown = 0f
    private var segmentPeak = 0f
    private var phase = 0f
    private var seed = 0
    private var lastLoud = 0L
    private var lastFrame = 0L
    private var folded = 0f // 0 open, 1 collapsed onto the front line

    /** True while recording; when false the front line rests flat and dashed. */
    var listening = false
        set(value) {
            field = value
            invalidate()
        }

    private val animate get() = ValueAnimator.areAnimatorsEnabled()

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = context.getColor(color)
        style = Paint.Style.STROKE
        strokeWidth = width * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Loudness 0..1 from the recorder, about 20 times a second. */
    fun setLevel(level: Float) {
        target = if (animate) level else stepped(level, 5)
        // solid only for what the recorder keeps as speech, so softer talk shows dashed: speak up
        if (target >= SPEECH) lastLoud = SystemClock.uptimeMillis()
        segmentPeak = maxOf(segmentPeak, target)
        segment.pushCapped(target, 1200) // a minute of speech is plenty for one line
        if (!animate) shown = target
        invalidate()
    }

    /** Carries the stack over when the screen is rebuilt for the other display, so folding the phone loses no lines. */
    fun takeOver(old: PulsePlotView) {
        frozen += old.frozen
        segment += old.segment
        seed = old.seed
        listening = old.listening
    }

    /** A sentence finished: its loudness over time becomes a permanent line. */
    fun freeze() {
        frozen.pushCapped(voiceRidge(segment) ?: ridge(FROZEN_PEAK, seed++, phase), 15)
        segment.clear()
        segmentPeak = 0f
        invalidate()
    }

    /** Collapse the stack onto the front line, then run [done]. Instant when animations are off. */
    fun fold(done: () -> Unit) {
        if (!animate) return done()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator(2.5f)
            addUpdateListener { folded = it.animatedValue as Float; invalidate() }
            doOnEnd(done)
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (animate) {
            val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceAtMost(64).toFloat()
            shown += (target - shown) * (1 - exp(-dt / 90f))
            phase += dt / 1000f * (0.8f + 6f * shown)
            target *= exp(-dt / 550f) // decay between the recorder's ~20 reports a second; by time, so 60 and 120Hz match
        }
        lastFrame = now
        // dashed means "listening, nothing heard"; the fold is a filed capture, so it stays solid
        val quiet = folded == 0f && (!listening || now - lastLoud > 1200)

        val left = paddingLeft.toFloat()
        val span = width - paddingLeft - paddingRight.toFloat()
        val bottom = height - paddingBottom - livePaint.strokeWidth
        val band = bottom - paddingTop
        // tall bands (main screen) carry sixteen lines so the sheet starts close under the caption; short ones
        // (the cover screen) keep them tight and show six
        val maxLines = if (band < 220 * dp) 6 else 16
        val spacing = (band / (maxLines + RIDGE - 1)).coerceIn(minSpacing, 24 * dp)
        val count = lineCountFor(band, spacing, maxLines)
        // the tallest ridge reaches the top of the band, so the plot owns its space instead of leaving a void above
        val lift = maxOf(RIDGE * spacing, band - (count - 1) * spacing) * (1 - folded)
        // while the mic is open the line never lies dead: room noise keeps a small drift under the dash
        val liveLevel = when {
            !quiet -> (shown * LIVE_GAIN).coerceAtMost(1f)
            listening -> 0.04f + 0.5f * shown
            else -> 0f
        }
        val live = ridge(liveLevel, seed, phase)
        emptyPaint.alpha = ((1 - folded) * 255).toInt() // empty slots fade instead of piling into a band as they fold


        fun trace(y: Float, heights: FloatArray?) {
            path.reset()
            path.moveTo(left, y)
            if (heights == null) path.lineTo(left + span, y)
            else heights.forEachIndexed { i, h -> path.lineTo(left + span * i / (POINTS - 1f), y - h * lift) }
        }

        // back to front: each line's ground-coloured fill hides whatever it rises in front of
        for (k in 0 until count) {
            val fromFront = count - 1 - k
            val y = bottom - fromFront * spacing * (1 - folded)
            val heights = when {
                fromFront == 0 -> live
                fromFront <= frozen.size -> frozen[frozen.size - fromFront]
                else -> null
            }
            trace(y, heights)
            if (heights != null) {
                path.lineTo(left + span, height.toFloat())
                path.lineTo(left, height.toFloat())
                path.close()
                canvas.drawPath(path, fill)
                trace(y, heights)
            }
            val paint = when {
                fromFront == 0 -> livePaint.apply { pathEffect = if (quiet) quietDash else null }
                heights == null -> emptyPaint
                else -> frozenPaint
            }
            canvas.drawPath(path, paint)
        }
        if (animate && (listening || folded in 0.001f..0.999f)) postInvalidateOnAnimation()
    }
}

private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = action()
})
