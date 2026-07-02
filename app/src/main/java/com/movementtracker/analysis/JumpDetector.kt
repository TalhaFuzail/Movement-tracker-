package com.movementtracker.analysis

import kotlin.math.max

/**
 * Detects vertical jumps from the hip midpoint's trajectory: the hips rise
 * sharply off a slowly-tracked standing baseline, peak, and come back down.
 * The peak rise in metres (via the current calibration) approximates the
 * centre-of-mass jump height — no ball or extra hardware needed.
 */
class JumpDetector(
    private val onJump: (tSec: Double, heightM: Double) -> Unit,
) {

    private var baselineY = Double.NaN
    private var airborne = false
    private var takeoffSec = 0.0
    private var peakRiseM = 0.0
    private var lastTSec = -1.0

    /** [hipY] is the hip midpoint's vertical position in view pixels (down is +). */
    fun update(tSec: Double, hipY: Double, metersPerPixel: Double?) {
        if (metersPerPixel == null) return
        if (lastTSec >= 0 && tSec - lastTSec > RESET_GAP_SEC) reset()
        lastTSec = tSec

        if (baselineY.isNaN()) {
            baselineY = hipY
            return
        }

        val riseM = (baselineY - hipY) * metersPerPixel
        if (!airborne) {
            if (riseM > MIN_JUMP_M) {
                airborne = true
                takeoffSec = tSec
                peakRiseM = riseM
            } else {
                // Track slow posture/framing changes without chasing a jump.
                baselineY = baselineY * BASELINE_KEEP + hipY * (1 - BASELINE_KEEP)
            }
        } else {
            peakRiseM = max(peakRiseM, riseM)
            val flightSec = tSec - takeoffSec
            if (riseM < LANDED_BELOW_M) {
                airborne = false
                if (peakRiseM <= MAX_JUMP_M && flightSec in MIN_FLIGHT_SEC..MAX_FLIGHT_SEC) {
                    onJump(tSec, peakRiseM)
                }
            } else if (flightSec > MAX_FLIGHT_SEC) {
                // Not a jump (walked off, tracking glitch): re-anchor.
                reset()
            }
        }
    }

    private fun reset() {
        baselineY = Double.NaN
        airborne = false
        peakRiseM = 0.0
    }

    private companion object {
        const val MIN_JUMP_M = 0.15
        const val MAX_JUMP_M = 1.4
        const val LANDED_BELOW_M = 0.05
        const val MIN_FLIGHT_SEC = 0.25
        const val MAX_FLIGHT_SEC = 1.4
        const val BASELINE_KEEP = 0.95
        const val RESET_GAP_SEC = 0.6
    }
}
