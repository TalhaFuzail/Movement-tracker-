package com.movementtracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.max

/**
 * Transparent overlay on top of the camera preview. Draws the detected pose
 * skeleton, the tracked ball, and — in calibration mode — the two reference
 * points the user taps.
 *
 * All tracking data arrives in upright *image* coordinates (the space the
 * speed math runs in) and is mapped to screen pixels only here, at draw
 * time. PreviewView fills the screen with a centre-crop; this view applies
 * the same transform. Calibration taps are converted the other way so the
 * saved scale is layout-independent.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Subtle overlay: thin white skeleton, a single accent for the ball,
    // muted red only for calibration markers.
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9FFFFFF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val calibrationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
        strokeWidth = 4f
        style = Paint.Style.FILL_AND_STROKE
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private var imageWidth = 0
    private var imageHeight = 0

    /** Pose landmarks in *image* coordinates, keyed by PoseLandmark type. */
    private var landmarks: Map<Int, PointF> = emptyMap()

    /** Ball bounding box in *image* coordinates, or null when not tracked. */
    private var ballBox: RectF? = null

    /** Second player's bounding box in *image* coordinates, if tracked. */
    private var player2Box: RectF? = null

    /** Recent ball positions (oldest first) in *image* coordinates. */
    private var ballTrail: List<PointF> = emptyList()

    var calibrationMode = false
        set(value) {
            field = value
            if (value) tappedViewPoints.clear()
            invalidate()
        }

    /** Taps in view coordinates, kept only for drawing the markers. */
    private val tappedViewPoints = mutableListOf<PointF>()

    /** Called with both calibration points in *image* coordinates. */
    var onCalibrationPointsReady: ((PointF, PointF) -> Unit)? = null

    fun setSourceImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    private fun scale(): Float {
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return 0f
        return max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
    }

    /** Maps a point from upright image coordinates to view coordinates. */
    fun imageToView(p: PointF): PointF {
        val s = scale()
        if (s == 0f) return p
        val dx = (width - imageWidth * s) / 2f
        val dy = (height - imageHeight * s) / 2f
        return PointF(p.x * s + dx, p.y * s + dy)
    }

    /** Maps a point from view coordinates back to upright image coordinates. */
    fun viewToImage(p: PointF): PointF {
        val s = scale()
        if (s == 0f) return p
        val dx = (width - imageWidth * s) / 2f
        val dy = (height - imageHeight * s) / 2f
        return PointF((p.x - dx) / s, (p.y - dy) / s)
    }

    fun update(
        imageLandmarks: Map<Int, PointF>,
        imageBallBox: RectF?,
        imagePlayer2Box: RectF? = null,
        imageBallTrail: List<PointF> = emptyList(),
    ) {
        landmarks = imageLandmarks
        ballBox = imageBallBox
        player2Box = imagePlayer2Box
        ballTrail = imageBallTrail
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale() == 0f && landmarks.isNotEmpty()) return

        for ((a, b) in SKELETON_CONNECTIONS) {
            val pa = landmarks[a]?.let(::imageToView) ?: continue
            val pb = landmarks[b]?.let(::imageToView) ?: continue
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, skeletonPaint)
        }
        for (p in landmarks.values) {
            val v = imageToView(p)
            canvas.drawCircle(v.x, v.y, 5f, jointPaint)
        }

        // Trail fades from transparent (oldest) to solid (newest).
        for (i in 1 until ballTrail.size) {
            trailPaint.alpha = (40 + 180 * i / ballTrail.size).coerceAtMost(220)
            val a = imageToView(ballTrail[i - 1])
            val b = imageToView(ballTrail[i])
            canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
        }

        ballBox?.let { box ->
            val tl = imageToView(PointF(box.left, box.top))
            val br = imageToView(PointF(box.right, box.bottom))
            val radius = max(br.x - tl.x, br.y - tl.y) / 2f + 8f
            canvas.drawCircle((tl.x + br.x) / 2f, (tl.y + br.y) / 2f, radius, ballPaint)
        }

        player2Box?.let { box ->
            val tl = imageToView(PointF(box.left, box.top))
            val br = imageToView(PointF(box.right, box.bottom))
            canvas.drawRoundRect(RectF(tl.x, tl.y, br.x, br.y), 18f, 18f, skeletonPaint)
        }

        if (calibrationMode) {
            for (p in tappedViewPoints) {
                canvas.drawCircle(p.x, p.y, 14f, calibrationPaint)
            }
            if (tappedViewPoints.size == 2) {
                val (a, b) = tappedViewPoints
                canvas.drawLine(a.x, a.y, b.x, b.y, calibrationPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrationMode) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN && tappedViewPoints.size < 2) {
            tappedViewPoints.add(PointF(event.x, event.y))
            invalidate()
            if (tappedViewPoints.size == 2) {
                onCalibrationPointsReady?.invoke(
                    viewToImage(tappedViewPoints[0]),
                    viewToImage(tappedViewPoints[1]),
                )
            }
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private companion object {
        val SKELETON_CONNECTIONS = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
        )
    }
}
