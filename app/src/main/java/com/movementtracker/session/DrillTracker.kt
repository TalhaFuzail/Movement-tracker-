package com.movementtracker.session

/**
 * A practice drill: a fixed number of attempts (kicks/bowls/throws) against
 * a target ball speed. Attempts come from the same ball-event detection the
 * session recorder uses; the UI beeps on each and shows a scorecard at the
 * end. Turns measuring into practising.
 */
class DrillTracker(
    val targetCount: Int,
    val targetKmh: Double,
    /** A friend's scorecard to beat, when this drill was started from a challenge code. */
    val rival: ChallengeResult? = null,
) {

    private val attempts = mutableListOf<Double>()

    val attemptCount: Int get() = attempts.size
    val isComplete: Boolean get() = attempts.size >= targetCount
    val bestKmh: Double get() = attempts.maxOrNull() ?: 0.0
    val averageKmh: Double get() = if (attempts.isEmpty()) 0.0 else attempts.average()
    val hitCount: Int get() = attempts.count { it >= targetKmh }

    /** Records one attempt; returns true when it met the target speed. */
    fun record(peakBallKmh: Double): Boolean {
        if (!isComplete) attempts.add(peakBallKmh)
        return peakBallKmh >= targetKmh
    }
}
