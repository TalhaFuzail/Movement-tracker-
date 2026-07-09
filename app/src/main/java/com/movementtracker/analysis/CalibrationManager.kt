package com.movementtracker.analysis

import android.graphics.PointF
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Maintains the pixels-to-metres scale used to convert on-screen motion into
 * real-world speed. All pixel values are in upright *image* coordinates, so
 * the scale is independent of screen layout and survives rotation.
 *
 * Two sources, in priority order:
 *  1. Manual calibration — the user marks two points a known distance apart
 *     (e.g. goal posts, cones), or AR measures the distance to the surface.
 *     Most accurate; survives until cleared.
 *  2. Auto calibration — estimated from the player's apparent height using
 *     [assumedPlayerHeightMeters]. Rough, but zero setup.
 *
 * Auto calibration measures the eye-to-ankle span — the pose model has no
 * top-of-head or sole landmarks — and converts it to stature with a standard
 * anthropometric ratio. It only updates when the eyes and ankles are actually
 * detected and the body is upright (shoulders above hips above knees above
 * ankles): estimating "height" from a torso with the legs out of frame, or
 * from a crouching player, used to inflate every speed readout.
 */
class CalibrationManager(
    var assumedPlayerHeightMeters: Double = 1.70,
) {

    private var manualMetersPerPixel: Double? = null
    private var autoMetersPerPixel: Double? = null

    val isManual: Boolean get() = manualMetersPerPixel != null
    val isCalibrated: Boolean get() = metersPerPixel != null

    /** Current best scale, or null if nothing has been observed yet. */
    val metersPerPixel: Double?
        get() = manualMetersPerPixel ?: autoMetersPerPixel

    /** True when the manual scale came from AR measurement, not user taps. */
    var isFromAr = false
        private set

    fun setManual(knownDistanceMeters: Double, measuredDistancePixels: Double) {
        if (knownDistanceMeters > 0 && measuredDistancePixels > 1) {
            manualMetersPerPixel = knownDistanceMeters / measuredDistancePixels
            isFromAr = false
        }
    }

    /** Directly sets the image-space scale (used by AR calibration). */
    fun setManualMetersPerPixel(value: Double, fromAr: Boolean) {
        if (value > 0) {
            manualMetersPerPixel = value
            isFromAr = fromAr
        }
    }

    fun clearManual() {
        manualMetersPerPixel = null
        isFromAr = false
    }

    /**
     * Feeds one frame's pose landmarks (image coordinates, keyed by
     * [PoseLandmark] type). Ignored while manual calibration is active, when
     * key landmarks are missing, or when the player isn't standing upright.
     * Uses a slow rolling average because apparent height fluctuates with pose.
     */
    fun updateAuto(landmarks: Map<Int, PointF>) {
        val eyeY = averageY(landmarks, PoseLandmark.LEFT_EYE, PoseLandmark.RIGHT_EYE) ?: return
        val ankleY = averageY(landmarks, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE) ?: return
        val shoulderY =
            averageY(landmarks, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return
        val hipY = averageY(landmarks, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return
        val kneeY = averageY(landmarks, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE) ?: return

        val span = ankleY - eyeY
        if (span < MIN_PERSON_PIXELS) return

        // Upright check: each body segment must sit clearly below the one
        // above it (screen y grows downward). A bent/crouched/mid-dive pose
        // would read as a shorter person and corrupt the scale.
        val margin = span * MIN_SEGMENT_FRACTION
        val upright = shoulderY > eyeY && hipY - shoulderY > margin &&
            kneeY - hipY > margin && ankleY - kneeY > margin
        if (!upright) return

        val estimate = assumedPlayerHeightMeters * EYE_TO_ANKLE_STATURE_RATIO / span
        autoMetersPerPixel = autoMetersPerPixel?.let { it * (1 - EMA_ALPHA) + estimate * EMA_ALPHA }
            ?: estimate
    }

    private fun averageY(landmarks: Map<Int, PointF>, left: Int, right: Int): Double? {
        val l = landmarks[left]
        val r = landmarks[right]
        return when {
            l != null && r != null -> (l.y + r.y) / 2.0
            else -> null // one side occluded → the midpoint would be biased
        }
    }

    private companion object {
        const val MIN_PERSON_PIXELS = 80.0
        /**
         * Standing eye height ≈ 0.936 × stature, ankle ≈ 0.039 × stature,
         * so the visible eye-to-ankle span is ≈ 0.897 of the real height.
         */
        const val EYE_TO_ANKLE_STATURE_RATIO = 0.897
        /** Each segment (shoulder→hip→knee→ankle) must be at least this tall. */
        const val MIN_SEGMENT_FRACTION = 0.10
        const val EMA_ALPHA = 0.05
    }
}
