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
        val leftKnee: PointF?,
        val rightKnee: PointF?,
        val leftHip: PointF?,
        val rightHip: PointF?,
        val leftElbow: PointF?,
        val rightElbow: PointF?,
        val leftShoulder: PointF?,
        val rightShoulder: PointF?,
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
                    leftKnee = landmarks[PoseLandmark.LEFT_KNEE],
                    rightKnee = landmarks[PoseLandmark.RIGHT_KNEE],
                    leftHip = landmarks[PoseLandmark.LEFT_HIP],
                    rightHip = landmarks[PoseLandmark.RIGHT_HIP],
                    leftElbow = landmarks[PoseLandmark.LEFT_ELBOW],
                    rightElbow = landmarks[PoseLandmark.RIGHT_ELBOW],
                    leftShoulder = landmarks[PoseLandmark.LEFT_SHOULDER],
                    rightShoulder = landmarks[PoseLandmark.RIGHT_SHOULDER],
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
                    extras.putAll(shotTechnique(window))
                }
                bowl != null -> {
                    type = ActivityType.CRICKET_BOWL
                    extras["armSwingKmh"] = bowl.limbSpeedKmh
                    extras.putAll(bowlTechnique(window))
                }
            }
        }

        onEvent(eventTSec, type, peakBallKmh, approachKmh, extras)
    }

    // --- Technique metrics ---------------------------------------------------
    // Form measured at the strike moment (the frame where the striking limb
    // peaked in speed), so coaching tips can quote the athlete's own numbers.

    /** Knee angle at contact and how high the kicking foot rises afterwards. */
    private fun shotTechnique(window: List<Snapshot>): Map<String, Double> {
        val (leftKmh, leftIdx) = peakLimbSpeed(window) { it.leftAnkle }
        val (rightKmh, rightIdx) = peakLimbSpeed(window) { it.rightAnkle }
        val leftLeg = leftKmh >= rightKmh
        val contactIdx = if (leftLeg) leftIdx else rightIdx
        if (contactIdx < 0) return emptyMap()

        val out = mutableMapOf<String, Double>()
        val contact = window[contactIdx]
        angleDeg(
            if (leftLeg) contact.leftHip else contact.rightHip,
            if (leftLeg) contact.leftKnee else contact.rightKnee,
            if (leftLeg) contact.leftAnkle else contact.rightAnkle,
        )?.let { out["kneeAngleDeg"] = it }

        // Follow-through: the highest the kicking ankle rises above hip level
        // baseline after contact, as a percentage of body height.
        var highest = 0.0
        for (i in contactIdx until window.size) {
            val s = window[i]
            val ankle = (if (leftLeg) s.leftAnkle else s.rightAnkle) ?: continue
            val hip = (if (leftLeg) s.leftHip else s.rightHip) ?: continue
            if (s.personHeightPx <= 0f) continue
            // Screen y grows downward: hip.y - ankle.y > 0 means the foot is up.
            highest = max(highest, (hip.y - ankle.y).toDouble() / s.personHeightPx)
        }
        if (highest > 0) out["followThroughPct"] = (highest * 100).coerceAtMost(150.0)
        return out
    }

    /** Elbow angle near release and release height above the head. */
    private fun bowlTechnique(window: List<Snapshot>): Map<String, Double> {
        val (leftKmh, leftIdx) = peakLimbSpeed(window) { it.leftWrist }
        val (rightKmh, rightIdx) = peakLimbSpeed(window) { it.rightWrist }
        val leftArm = leftKmh >= rightKmh
        val releaseIdx = if (leftArm) leftIdx else rightIdx
        if (releaseIdx < 0) return emptyMap()

        val out = mutableMapOf<String, Double>()
        val release = window[releaseIdx]
        angleDeg(
            if (leftArm) release.leftShoulder else release.rightShoulder,
            if (leftArm) release.leftElbow else release.rightElbow,
            if (leftArm) release.leftWrist else release.rightWrist,
        )?.let { out["elbowAngleDeg"] = it }

        // Top of the arc: how far the wrist gets above the head, as a
        // percentage of body height — a proxy for a tall release.
        var highest = 0.0
        for (s in window) {
            val wrist = (if (leftArm) s.leftWrist else s.rightWrist) ?: continue
            val nose = s.noseY ?: continue
            if (s.personHeightPx <= 0f) continue
            highest = max(highest, (nose - wrist.y).toDouble() / s.personHeightPx)
        }
        if (highest > 0) out["releaseHeightPct"] = (highest * 100).coerceAtMost(100.0)
        return out
    }

    /** Angle at vertex [b] of the triangle a-b-c, in degrees, or null if unknown. */
    private fun angleDeg(a: PointF?, b: PointF?, c: PointF?): Double? {
        if (a == null || b == null || c == null) return null
        val v1x = (a.x - b.x).toDouble()
        val v1y = (a.y - b.y).toDouble()
        val v2x = (c.x - b.x).toDouble()
        val v2y = (c.y - b.y).toDouble()
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 < 1e-3 || n2 < 1e-3) return null
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cos))
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
    ): Double = peakLimbSpeed(window, limb).first

    /** Peak limb speed and the window index of the frame where it happened. */
    private fun peakLimbSpeed(
        window: List<Snapshot>,
        limb: (Snapshot) -> PointF?,
    ): Pair<Double, Int> {
        var peak = 0.0
        var peakIdx = -1
        for (i in 1 until window.size) {
            val a = limb(window[i - 1]) ?: continue
            val b = limb(window[i]) ?: continue
            val dt = window[i].tSec - window[i - 1].tSec
            if (dt < 1e-3 || dt > 0.2) continue
            val px = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            val kmh = px * window[i].metersPerPixel / dt * 3.6
            if (kmh > peak) {
                peak = kmh
                peakIdx = i
            }
        }
        return peak to peakIdx
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
