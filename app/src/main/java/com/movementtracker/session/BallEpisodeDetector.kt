package com.movementtracker.session

import kotlin.math.max

/**
 * Groups per-frame ball speeds into discrete "episodes" — a kick, throw or
 * bowl shows up as the ball speed spiking above [EPISODE_START_KMH] and later
 * dying down or the ball leaving the frame. One episode → one recorded event.
 *
 * Reports the peak ball speed and the player's speed just before the episode
 * (approach speed), which the suggestion engine uses.
 */
class BallEpisodeDetector(
    private val onEpisode: (tSec: Double, peakBallKmh: Double, approachPlayerKmh: Double) -> Unit,
) {

    private var episodeStartSec: Double? = null
    private var peakKmh = 0.0
    private var approachPlayerKmh = 0.0
    private var lastBallSeenSec = -1.0
    private var recentPlayerKmh = 0.0

    /** Call every frame; [ballKmh] is null when the ball isn't tracked. */
    fun update(tSec: Double, ballKmh: Double?, playerKmh: Double) {
        recentPlayerKmh = playerKmh

        if (ballKmh != null) {
            lastBallSeenSec = tSec
            if (episodeStartSec == null && ballKmh >= EPISODE_START_KMH) {
                episodeStartSec = tSec
                approachPlayerKmh = playerKmh
                peakKmh = ballKmh
            } else if (episodeStartSec != null) {
                peakKmh = max(peakKmh, ballKmh)
                if (ballKmh < EPISODE_END_KMH) finishEpisode(tSec)
            }
        } else if (episodeStartSec != null && tSec - lastBallSeenSec > BALL_LOST_SEC) {
            // Ball flew out of frame mid-episode — that's still a valid event.
            finishEpisode(tSec)
        }
    }

    private fun finishEpisode(tSec: Double) {
        val start = episodeStartSec ?: return
        episodeStartSec = null
        if (peakKmh >= MIN_EVENT_PEAK_KMH && tSec - start < MAX_EPISODE_SEC) {
            onEpisode(start, peakKmh, approachPlayerKmh)
        }
        peakKmh = 0.0
    }

    private companion object {
        const val EPISODE_START_KMH = 20.0
        const val EPISODE_END_KMH = 8.0
        const val MIN_EVENT_PEAK_KMH = 22.0
        const val BALL_LOST_SEC = 0.4
        const val MAX_EPISODE_SEC = 5.0
    }
}
