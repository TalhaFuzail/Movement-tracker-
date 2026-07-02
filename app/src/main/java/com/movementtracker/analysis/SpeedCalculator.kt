package com.movementtracker.analysis

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Turns a stream of timestamped 2D positions into a smoothed speed estimate.
 *
 * Speed is measured over a sliding time window rather than frame-to-frame,
 * which suppresses the pixel-level jitter of ML detections, then lightly
 * exponentially smoothed for a stable on-screen readout.
 */
class SpeedCalculator(
    /** Window over which displacement is measured. Shorter = more responsive. */
    private val windowSeconds: Double = 0.3,
    /** Exponential smoothing factor for the displayed value (0..1, higher = snappier). */
    private val smoothing: Double = 0.4,
) {

    private data class Sample(val tSec: Double, val x: Float, val y: Float)

    private val samples = ArrayDeque<Sample>()

    /** Smoothed speed in metres/second (0 until enough samples arrive). */
    var metersPerSecond: Double = 0.0
        private set

    val kmPerHour: Double get() = metersPerSecond * 3.6

    /**
     * Adds a new observation and returns the updated smoothed speed in m/s.
     * If the gap since the last sample exceeds [MAX_GAP_SECONDS] the history
     * is reset (the subject was lost, don't fabricate a huge jump).
     */
    fun add(tSec: Double, point: PointF, metersPerPixel: Double): Double {
        val last = samples.lastOrNull()
        if (last != null && tSec - last.tSec > MAX_GAP_SECONDS) {
            reset()
        }

        samples.addLast(Sample(tSec, point.x, point.y))
        while (samples.size > 2 && samples.first().tSec < tSec - windowSeconds) {
            samples.removeFirst()
        }

        val first = samples.first()
        val newest = samples.last()
        val dt = newest.tSec - first.tSec
        if (dt < 1e-3 || samples.size < 2) return metersPerSecond

        val distPixels = hypot(
            (newest.x - first.x).toDouble(),
            (newest.y - first.y).toDouble(),
        )
        val instantaneous = distPixels * metersPerPixel / dt
        metersPerSecond = smoothing * instantaneous + (1 - smoothing) * metersPerSecond
        return metersPerSecond
    }

    fun reset() {
        samples.clear()
        metersPerSecond = 0.0
    }

    private companion object {
        const val MAX_GAP_SECONDS = 0.5
    }
}
