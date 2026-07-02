package com.movementtracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.movementtracker.R
import kotlin.math.max

/**
 * A 3×3 heat grid of shot placement for the session detail screen — the
 * same zones the live target overlay uses (row-major from top-left). Cell
 * shading scales with how many attempts landed there; the count is printed
 * in the cell. Pure Canvas, matching SpeedChartView's style.
 */
class PlacementGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var counts = IntArray(9)

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
    }

    /** [values] indexed 0..8, row-major from the top-left zone. */
    fun setCounts(values: IntArray) {
        counts = IntArray(9) { values.getOrElse(it) { 0 } }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellW = (width - paddingLeft - paddingRight) / 3f
        val cellH = (height - paddingTop - paddingBottom) / 3f
        if (cellW <= 0 || cellH <= 0) return
        val maxCount = max(1, counts.max())

        for (row in 0..2) {
            for (col in 0..2) {
                val n = counts[row * 3 + col]
                val left = paddingLeft + col * cellW
                val top = paddingTop + row * cellH
                if (n > 0) {
                    cellPaint.alpha = 30 + 170 * n / maxCount
                    canvas.drawRect(left, top, left + cellW, top + cellH, cellPaint)
                }
                canvas.drawRect(left, top, left + cellW, top + cellH, gridPaint)
                if (n > 0) {
                    canvas.drawText(
                        n.toString(),
                        left + cellW / 2f,
                        top + cellH / 2f + countPaint.textSize / 3f,
                        countPaint,
                    )
                }
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
