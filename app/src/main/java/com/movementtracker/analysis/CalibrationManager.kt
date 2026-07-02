package com.movementtracker.analysis

/**
 * Maintains the pixels-to-metres scale used to convert on-screen motion into
 * real-world speed.
 *
 * Two sources, in priority order:
 *  1. Manual calibration — the user marks two points a known distance apart
 *     (e.g. goal posts, cones). Most accurate; survives until cleared.
 *  2. Auto calibration — while a full body is visible we estimate scale from
 *     the player's apparent height, assuming [assumedPlayerHeightMeters].
 *     Rough, but gives sensible numbers with zero setup.
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

    fun setManual(knownDistanceMeters: Double, measuredDistancePixels: Double) {
        if (knownDistanceMeters > 0 && measuredDistancePixels > 1) {
            manualMetersPerPixel = knownDistanceMeters / measuredDistancePixels
        }
    }

    fun clearManual() {
        manualMetersPerPixel = null
    }

    /**
     * Feeds one frame's estimate of the player's height in pixels
     * (head-to-ankle extent). Ignored while manual calibration is active.
     * Uses a slow rolling average because apparent height fluctuates with pose.
     */
    fun updateAuto(personPixelHeight: Float) {
        if (personPixelHeight < MIN_PERSON_PIXELS) return
        val estimate = assumedPlayerHeightMeters / personPixelHeight
        autoMetersPerPixel = autoMetersPerPixel?.let { it * 0.95 + estimate * 0.05 } ?: estimate
    }

    private companion object {
        const val MIN_PERSON_PIXELS = 80f
    }
}
