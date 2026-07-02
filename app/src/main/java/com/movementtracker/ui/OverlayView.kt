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

    /** Recent ball positions (oldest first) in *view* coordinates. */
    private var ballTrail: List<PointF> = emptyList()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2AAE7E")
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    var calibrationMode = false
        set(value) {
            field = value
            if (value) calibrationPoints.clear()
            invalidate()
        }

    val calibrationPoints = mutableListOf<PointF>()

    /** Called when the user has tapped the second calibration point. */
    var onCalibrationPointsReady: ((PointF, PointF) -> Unit)? = null

    // --- Shot placement target ---------------------------------------------
    // A goal (or any target area) marked by tapping two opposite corners.
    // While set, it is drawn as a 3×3 grid and ball positions map to zones.

    var targetMode = false
        set(value) {
            field = value
            if (value) targetCorners.clear()
            invalidate()
        }

    private val targetCorners = mutableListOf<PointF>()

    /** Called when the user has tapped the second target corner. */
    var onTargetCornersReady: ((PointF, PointF) -> Unit)? = null

    /** Target area in view coordinates, or null when no target is set. */
    var targetRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    val hasTarget: Boolean get() = targetRect != null

    /** Zone index (0..8, row-major from top-left) for a view point, or null if outside. */
    fun zoneAt(p: PointF): Int? {
        val rect = targetRect ?: return null
        if (!rect.contains(p.x, p.y)) return null
        val col = ((p.x - rect.left) / rect.width() * 3).toInt().coerceIn(0, 2)
        val row = ((p.y - rect.top) / rect.height() * 3).toInt().coerceIn(0, 2)
        return row * 3 + col
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0B44A")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val targetGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90E0B44A")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

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
        viewBallTrail: List<PointF> = emptyList(),
    ) {
        landmarks = viewLandmarks
        ballBox = viewBallBox
        player2Box = viewPlayer2Box
        ballTrail = viewBallTrail
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

        // Trail fades from transparent (oldest) to solid (newest).
        for (i in 1 until ballTrail.size) {
            trailPaint.alpha = (40 + 180 * i / ballTrail.size).coerceAtMost(220)
            val a = ballTrail[i - 1]
            val b = ballTrail[i]
            canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
        }

        ballBox?.let { box ->
            val radius = max(box.width(), box.height()) / 2f + 8f
            canvas.drawCircle(box.centerX(), box.centerY(), radius, ballPaint)
        }

        player2Box?.let { box ->
            canvas.drawRoundRect(box, 18f, 18f, skeletonPaint)
        }

        targetRect?.let { rect ->
            canvas.drawRect(rect, targetPaint)
            for (i in 1..2) {
                val gx = rect.left + rect.width() * i / 3f
                canvas.drawLine(gx, rect.top, gx, rect.bottom, targetGridPaint)
                val gy = rect.top + rect.height() * i / 3f
                canvas.drawLine(rect.left, gy, rect.right, gy, targetGridPaint)
            }
        }
        if (targetMode) {
            for (p in targetCorners) {
                canvas.drawCircle(p.x, p.y, 14f, targetPaint)
            }
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
        if (calibrationMode) {
            if (event.action == MotionEvent.ACTION_DOWN && calibrationPoints.size < 2) {
                calibrationPoints.add(PointF(event.x, event.y))
                invalidate()
                if (calibrationPoints.size == 2) {
                    onCalibrationPointsReady?.invoke(calibrationPoints[0], calibrationPoints[1])
                }
            }
            return true
        }
        if (targetMode) {
            if (event.action == MotionEvent.ACTION_DOWN && targetCorners.size < 2) {
                targetCorners.add(PointF(event.x, event.y))
                invalidate()
                if (targetCorners.size == 2) {
                    onTargetCornersReady?.invoke(targetCorners[0], targetCorners[1])
                }
            }
            return true
        }
        return super.onTouchEvent(event)
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
