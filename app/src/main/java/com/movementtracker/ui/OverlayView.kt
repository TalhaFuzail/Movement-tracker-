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
 * The analysis image and the preview view have different sizes; PreviewView
 * fills the screen with a centre-crop, so this view applies the same
 * transform to map image-space detections onto screen pixels.
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
        color = Color.parseColor("#2AAE7E")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val calibrationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C75450")
        strokeWidth = 4f
        style = Paint.Style.FILL_AND_STROKE
    }

    private var imageWidth = 0
    private var imageHeight = 0

    /** Pose landmarks in *view* coordinates, keyed by PoseLandmark type. */
    private var landmarks: Map<Int, PointF> = emptyMap()

    /** Ball bounding box in *view* coordinates, or null when not tracked. */
    private var ballBox: RectF? = null

    /** Second player's bounding box in *view* coordinates, if tracked. */
    private var player2Box: RectF? = null

    var calibrationMode = false
        set(value) {
            field = value
            if (value) calibrationPoints.clear()
            invalidate()
        }

    val calibrationPoints = mutableListOf<PointF>()

    /** Called when the user has tapped the second calibration point. */
    var onCalibrationPointsReady: ((PointF, PointF) -> Unit)? = null

    fun setSourceImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    /** Maps a point from upright image coordinates to view coordinates. */
    fun imageToView(p: PointF): PointF {
        if (imageWidth == 0 || imageHeight == 0) return p
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        return PointF(p.x * scale + dx, p.y * scale + dy)
    }

    fun update(
        viewLandmarks: Map<Int, PointF>,
        viewBallBox: RectF?,
        viewPlayer2Box: RectF? = null,
    ) {
        landmarks = viewLandmarks
        ballBox = viewBallBox
        player2Box = viewPlayer2Box
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for ((a, b) in SKELETON_CONNECTIONS) {
            val pa = landmarks[a] ?: continue
            val pb = landmarks[b] ?: continue
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, skeletonPaint)
        }
        for (p in landmarks.values) {
            canvas.drawCircle(p.x, p.y, 5f, jointPaint)
        }

        ballBox?.let { box ->
            val radius = max(box.width(), box.height()) / 2f + 8f
            canvas.drawCircle(box.centerX(), box.centerY(), radius, ballPaint)
        }

        player2Box?.let { box ->
            canvas.drawRoundRect(box, 18f, 18f, skeletonPaint)
        }

        if (calibrationMode) {
            for (p in calibrationPoints) {
                canvas.drawCircle(p.x, p.y, 14f, calibrationPaint)
            }
            if (calibrationPoints.size == 2) {
                val (a, b) = calibrationPoints
                canvas.drawLine(a.x, a.y, b.x, b.y, calibrationPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrationMode) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN && calibrationPoints.size < 2) {
            calibrationPoints.add(PointF(event.x, event.y))
            invalidate()
            if (calibrationPoints.size == 2) {
                onCalibrationPointsReady?.invoke(calibrationPoints[0], calibrationPoints[1])
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
