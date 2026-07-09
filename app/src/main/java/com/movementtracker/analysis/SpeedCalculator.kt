package com.movementtracker.analysis

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Turns a stream of timestamped 2D positions into a speed estimate.
 *
 * Velocity is the least-squares linear fit of position over a sliding time
 * window — with ~10 samples in the window this suppresses ML detection jitter
 * far better than the endpoint displacement it replaces, because every sample
 * contributes to the slope instead of only the two noisiest ones.
 *
 * Two readouts are kept:
 *  - [rawMetersPerSecond]: the unsmoothed fit, used for peaks and event
 *    detection so a one-frame maximum isn't averaged away.
 *  - [metersPerSecond]: lightly exponentially smoothed, for a stable
 *    on-screen number.
 *
 * Samples implying a physically impossible speed (a tracker re-lock onto a
 * different object, an ML glitch) are rejected instead of being read as a
 * huge burst; several impossible samples in a row mean the subject really
 * changed, so the history is restarted there.
 */
class SpeedCalculator(
    /** Window over which velocity is fitted. Shorter = more responsive. */
    private val windowSeconds: Double = 0.3,
    /** Exponential smoothing factor for the displayed value (0..1, higher = snappier). */
    private val smoothing: Double = 0.4,
    /** Hard physical ceiling; samples implying more than this are glitches. */
    private val maxSpeedMetersPerSecond: Double = 60.0,
) {

    private data class Sample(val tSec: Double, val x: Float, val y: Float)

    private val samples = ArrayDeque<Sample>()
    private var outlierStreak = 0

    /** Smoothed speed in metres/second (0 until enough samples arrive). */
    var metersPerSecond: Double = 0.0
        private set

    /** Unsmoothed fitted speed — use for peak detection, not display. */
    var rawMetersPerSecond: Double = 0.0
        private set

    val kmPerHour: Double get() = metersPerSecond * 3.6
    val rawKmPerHour: Double get() = rawMetersPerSecond * 3.6

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

        // Physical plausibility gate: an instantaneous frame-to-frame speed
        // beyond the ceiling is a mistracked detection, not motion.
        val prev = samples.lastOrNull()
        if (prev != null) {
            val dt = tSec - prev.tSec
            if (dt > 1e-3) {
                val implied = hypot(
                    (point.x - prev.x).toDouble(),
                    (point.y - prev.y).toDouble(),
                ) * metersPerPixel / dt
                if (implied > maxSpeedMetersPerSecond) {
                    outlierStreak++
                    if (outlierStreak >= MAX_OUTLIER_STREAK) {
                        // Consistently "impossible" means the subject really
                        // is somewhere else now — restart tracking there.
                        reset()
                        samples.addLast(Sample(tSec, point.x, point.y))
                    }
                    return metersPerSecond
                }
            }
        }
        outlierStreak = 0

        samples.addLast(Sample(tSec, point.x, point.y))
        while (samples.size > 2 && samples.first().tSec < tSec - windowSeconds) {
            samples.removeFirst()
        }

        val span = samples.last().tSec - samples.first().tSec
        if (samples.size < 2 || span < MIN_SPAN_SECONDS) return metersPerSecond

        rawMetersPerSecond = fittedPixelsPerSecond() * metersPerPixel
        metersPerSecond = smoothing * rawMetersPerSecond + (1 - smoothing) * metersPerSecond
        return metersPerSecond
    }

    /**
     * Least-squares slope of x(t) and y(t) over the window; the speed is the
     * magnitude of the fitted velocity vector, in pixels/second.
     */
    private fun fittedPixelsPerSecond(): Double {
        val n = samples.size
        var meanT = 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (s in samples) {
            meanT += s.tSec
            meanX += s.x
            meanY += s.y
        }
        meanT /= n
        meanX /= n
        meanY /= n

        var covTX = 0.0
        var covTY = 0.0
        var varT = 0.0
        for (s in samples) {
            val dt = s.tSec - meanT
            covTX += dt * (s.x - meanX)
            covTY += dt * (s.y - meanY)
            varT += dt * dt
        }
        if (varT < 1e-9) return 0.0
        return hypot(covTX / varT, covTY / varT)
    }

    fun reset() {
        samples.clear()
        metersPerSecond = 0.0
        rawMetersPerSecond = 0.0
        outlierStreak = 0
    }

    private companion object {
        const val MAX_GAP_SECONDS = 0.5
        /** Below this the slope is dominated by noise; wait for more data. */
        const val MIN_SPAN_SECONDS = 0.04
        const val MAX_OUTLIER_STREAK = 3
    }
}
