package com.movementtracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.RecordedEvent
import com.movementtracker.session.SpeedSample
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Player-speed-over-time line chart for the session detail screen.
 * Ball events (shots, bowls) are marked along the baseline so speed
 * bursts can be matched to what happened. Pure Canvas, no chart library.
 */
class SpeedChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var samples: List<SpeedSample> = emptyList()
    private var events: List<RecordedEvent> = emptyList()
    private var durationSec = 0.0
    private var maxKmh = 1.0

    private val accent = ContextCompat.getColor(context, R.color.accent)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent and 0x00FFFFFF or 0x14000000
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = sp(11f)
    }
    private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    fun setData(samples: List<SpeedSample>, events: List<RecordedEvent>, durationSec: Double) {
        this.samples = samples
        this.events = events
        this.durationSec = max(durationSec, samples.lastOrNull()?.tOffsetSec ?: 0.0)
        // Round the ceiling up to a tidy grid value so the top label reads well.
        maxKmh = max(5.0, ceil((samples.maxOfOrNull { it.playerKmh } ?: 0.0) / 5.0) * 5.0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2 || durationSec <= 0) return

        val labelH = labelPaint.textSize + dp(6f)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + labelH
        val bottom = height - paddingBottom - labelH
        if (right - left < dp(40f) || bottom - top < dp(40f)) return

        fun x(t: Double) = left + ((t / durationSec) * (right - left)).toFloat()
        fun y(kmh: Double) = bottom - ((kmh / maxKmh) * (bottom - top)).toFloat()

        // Horizontal grid: baseline, midline, top line with km/h labels.
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            val gy = y(maxKmh * fraction)
            canvas.drawLine(left, gy, right, gy, gridPaint)
            if (fraction > 0.0) {
                canvas.drawText(
                    String.format(Locale.US, "%.0f km/h", maxKmh * fraction),
                    left, gy - dp(4f), labelPaint,
                )
            }
        }

        linePath.reset()
        samples.forEachIndexed { i, s ->
            if (i == 0) linePath.moveTo(x(s.tOffsetSec), y(s.playerKmh))
            else linePath.lineTo(x(s.tOffsetSec), y(s.playerKmh))
        }
        fillPath.set(linePath)
        fillPath.lineTo(x(samples.last().tOffsetSec), bottom)
        fillPath.lineTo(x(samples.first().tOffsetSec), bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Ball events as dots on the baseline (sprints are visible in the line itself).
        events.filter { it.type != ActivityType.SPRINT }.forEach { e ->
            eventPaint.color = accent
            canvas.drawCircle(x(e.tOffsetSec), bottom, dp(3.5f), eventPaint)
        }

        // Time axis: start and end labels.
        val endLabel = String.format(
            Locale.US, "%d:%02d", (durationSec / 60).toInt(), (durationSec % 60).toInt(),
        )
        canvas.drawText("0:00", left, bottom + labelH, labelPaint)
        canvas.drawText(
            endLabel,
            right - labelPaint.measureText(endLabel), bottom + labelH, labelPaint,
        )
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
