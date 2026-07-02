package com.movementtracker.analysis

import android.graphics.PointF
import com.google.mlkit.vision.pose.PoseLandmark
import com.movementtracker.session.ActivityType
import com.movementtracker.session.BallEpisodeDetector
import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides *what* the player just did when a fast ball event happens.
 *
 * Keeps a short rolling history of body landmarks. When the ball speed
 * spikes (episode detected), it looks at the second of motion leading up to
 * the spike:
 *
 *  - CRICKET_BOWL — a wrist travelled above the head with high arm speed
 *    just before release (the overarm bowling action).
 *  - SOCCER_SHOT — an ankle was next to the ball with a fast leg swing at
 *    the moment the ball accelerated.
 *  - BALL_EVENT — a fast ball with neither signature (e.g. a pass filmed
 *    from behind, or the body was partly out of frame).
 *
 * Sprints are detected separately by SessionRecorder from sustained speed.
 */
class ActivityClassifier(
    private val onEvent: (
        tSec: Double,
        type: ActivityType,
        peakBallKmh: Double,
        playerKmh: Double,
        extras: Map<String, Double>,
    ) -> Unit,
) {

    private data class Snapshot(
        val tSec: Double,
        val leftWrist: PointF?,
        val rightWrist: PointF?,
        val leftAnkle: PointF?,
        val rightAnkle: PointF?,
        val noseY: Float?,
        val personHeightPx: Float,
        val ballCenter: PointF?,
        val metersPerPixel: Double,
    )

    private val history = ArrayDeque<Snapshot>()

    private val episodes = BallEpisodeDetector { tSec, peakKmh, approachKmh ->
        classify(tSec, peakKmh, approachKmh)
    }

    /** Call once per frame with everything in the same (view) coordinate space. */
    fun update(
        tSec: Double,
        landmarks: Map<Int, PointF>,
        ballCenter: PointF?,
        ballKmh: Double?,
        playerKmh: Double,
        metersPerPixel: Double?,
    ) {
        if (metersPerPixel != null && landmarks.isNotEmpty()) {
            val ys = landmarks.values.map { it.y }
            history.addLast(
                Snapshot(
                    tSec = tSec,
                    leftWrist = landmarks[PoseLandmark.LEFT_WRIST],
                    rightWrist = landmarks[PoseLandmark.RIGHT_WRIST],
                    leftAnkle = landmarks[PoseLandmark.LEFT_ANKLE],
                    rightAnkle = landmarks[PoseLandmark.RIGHT_ANKLE],
                    noseY = landmarks[PoseLandmark.NOSE]?.y,
                    personHeightPx = ys.max() - ys.min(),
                    ballCenter = ballCenter,
                    metersPerPixel = metersPerPixel,
                )
            )
            while (history.isNotEmpty() && history.first().tSec < tSec - HISTORY_SECONDS) {
                history.removeFirst()
            }
        }
        episodes.update(tSec, ballKmh, playerKmh)
    }

    private fun classify(eventTSec: Double, peakBallKmh: Double, approachKmh: Double) {
        val window = history.filter { it.tSec in (eventTSec - LOOKBACK_SECONDS)..(eventTSec + 0.15) }

        val extras = mutableMapOf("approachKmh" to approachKmh)
        var type = ActivityType.BALL_EVENT

        if (window.size >= 3) {
            val bowl = bowlingEvidence(window)
            val shot = shotEvidence(window)
            when {
                shot != null && (bowl == null || shot.strength >= bowl.strength) -> {
                    type = ActivityType.SOCCER_SHOT
                    extras["footKmh"] = shot.limbSpeedKmh
                }
                bowl != null -> {
                    type = ActivityType.CRICKET_BOWL
                    extras["armSwingKmh"] = bowl.limbSpeedKmh
                }
            }
        }

        onEvent(eventTSec, type, peakBallKmh, approachKmh, extras)
    }

    private data class Evidence(val strength: Double, val limbSpeedKmh: Double)

    /** Overarm action: wrist above the nose plus a fast arm swing. */
    private fun bowlingEvidence(window: List<Snapshot>): Evidence? {
        var wristAboveHead = false
        for (s in window) {
            val nose = s.noseY ?: continue
            val margin = s.personHeightPx * 0.05f
            if ((s.leftWrist?.y ?: Float.MAX_VALUE) < nose - margin ||
                (s.rightWrist?.y ?: Float.MAX_VALUE) < nose - margin
            ) {
                wristAboveHead = true
                break
            }
        }
        if (!wristAboveHead) return null

        val armKmh = max(
            peakLimbSpeedKmh(window) { it.leftWrist },
            peakLimbSpeedKmh(window) { it.rightWrist },
        )
        if (armKmh < MIN_SWING_KMH) return null
        return Evidence(strength = armKmh, limbSpeedKmh = armKmh)
    }

    /** Kicking action: an ankle right next to the ball plus a fast leg swing. */
    private fun shotEvidence(window: List<Snapshot>): Evidence? {
        var ankleNearBall = false
        for (s in window) {
            val ball = s.ballCenter ?: continue
            val reach = s.personHeightPx * 0.22f
            if (distance(s.leftAnkle, ball) < reach || distance(s.rightAnkle, ball) < reach) {
                ankleNearBall = true
                break
            }
        }
        if (!ankleNearBall) return null

        val footKmh = max(
            peakLimbSpeedKmh(window) { it.leftAnkle },
            peakLimbSpeedKmh(window) { it.rightAnkle },
        )
        if (footKmh < MIN_SWING_KMH) return null
        // Contact evidence is stronger than posture evidence, so boost it.
        return Evidence(strength = footKmh * 1.5, limbSpeedKmh = footKmh)
    }

    private fun peakLimbSpeedKmh(
        window: List<Snapshot>,
        limb: (Snapshot) -> PointF?,
    ): Double {
        var peak = 0.0
        for (i in 1 until window.size) {
            val a = limb(window[i - 1]) ?: continue
            val b = limb(window[i]) ?: continue
            val dt = window[i].tSec - window[i - 1].tSec
            if (dt < 1e-3 || dt > 0.2) continue
            val px = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            peak = max(peak, px * window[i].metersPerPixel / dt * 3.6)
        }
        return peak
    }

    private fun distance(a: PointF?, b: PointF): Float {
        if (a == null) return Float.MAX_VALUE
        return hypot(a.x - b.x, a.y - b.y)
    }

    private companion object {
        const val HISTORY_SECONDS = 1.5
        const val LOOKBACK_SECONDS = 0.7
        /** A limb slower than this isn't a deliberate strike/bowl. */
        const val MIN_SWING_KMH = 10.0
    }
}
