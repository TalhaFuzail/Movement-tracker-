package com.movementtracker.analysis

import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.objects.DetectedObject
import kotlin.math.hypot

/**
 * Picks the detection most likely to be the ball out of the generic object
 * detector's output, and keeps following the same physical object across
 * frames using ML Kit tracking IDs (with a nearest-neighbour fallback when
 * the tracker re-assigns IDs mid-flight, which happens on fast kicks).
 *
 * Candidates are ranked in two tiers: detections whose classifier label is an
 * actual ball class (soccer ball, cricket ball, tennis ball...) are always
 * preferred; shape heuristics (roughly square aspect, small relative to the
 * frame) filter the rest and act as a fallback when classification flickers.
 */
class BallTracker {

    private var lastTrackingId: Int? = null
    private var lastCenter: PointF? = null
    private var lastSeenSec = -1.0

    /** True when the current pick started a new track (speed history must reset). */
    var startedNewTrack = false
        private set

    fun pick(
        objects: List<DetectedObject>,
        imageWidth: Int,
        imageHeight: Int,
        tSec: Double,
    ): DetectedObject? {
        startedNewTrack = false
        val frameArea = imageWidth.toFloat() * imageHeight.toFloat()

        val candidates = objects.filter { obj ->
            val box = obj.boundingBox
            val w = box.width().coerceAtLeast(1)
            val h = box.height().coerceAtLeast(1)
            val aspect = w.toFloat() / h
            aspect in 0.55f..1.8f && w * h < frameArea * MAX_BALL_AREA_FRACTION
        }

        if (candidates.isEmpty()) {
            if (lastSeenSec >= 0 && tSec - lastSeenSec > TRACK_TIMEOUT_SECONDS) {
                dropTrack()
            }
            return null
        }

        val previousCenter = lastCenter
        // Detections the classifier recognises as a ball outrank everything;
        // classification can flicker frame-to-frame, so an active tracking ID
        // still wins, and shape-only candidates remain as a fallback.
        val pool = candidates.filter(::hasBallLabel).ifEmpty { candidates }
        val picked =
            candidates.firstOrNull { it.trackingId != null && it.trackingId == lastTrackingId }
                ?: previousCenter?.let { prev ->
                    pool.minByOrNull { distance(center(it.boundingBox), prev) }
                }
                ?: pool.minByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return null

        val pickedCenter = center(picked.boundingBox)
        val idChanged = picked.trackingId == null || picked.trackingId != lastTrackingId
        val jumped = previousCenter != null &&
            distance(pickedCenter, previousCenter) > imageWidth * MAX_REACQUIRE_JUMP_FRACTION
        startedNewTrack = lastCenter == null || (idChanged && jumped)

        lastTrackingId = picked.trackingId
        lastCenter = pickedCenter
        lastSeenSec = tSec
        return picked
    }

    private fun dropTrack() {
        lastTrackingId = null
        lastCenter = null
        lastSeenSec = -1.0
    }

    private fun hasBallLabel(obj: DetectedObject) =
        obj.labels.any { it.text.lowercase() in BALL_LABELS }

    private fun center(box: Rect) = PointF(box.exactCenterX(), box.exactCenterY())

    private fun distance(a: PointF, b: PointF) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private companion object {
        const val MAX_BALL_AREA_FRACTION = 0.12f
        const val TRACK_TIMEOUT_SECONDS = 0.5
        const val MAX_REACQUIRE_JUMP_FRACTION = 0.35

        // Exact class names from the bundled labeler model ("Baseball bat",
        // "Balloon" etc. also contain "ball", hence an allowlist not contains()).
        val BALL_LABELS = setOf(
            "ball", "football", "soccer ball", "cricket ball", "tennis ball",
            "baseball", "golf ball", "volleyball", "basketball", "rugby ball",
        )
    }
}
