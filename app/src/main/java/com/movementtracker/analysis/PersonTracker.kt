package com.movementtracker.analysis

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.objects.DetectedObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks a second player. Pose detection follows one person only, but the
 * object detector sees everyone: detections the classifier labels "Person"
 * that don't overlap the pose-tracked player are second-player candidates,
 * followed across frames by tracking ID with a nearest-neighbour fallback.
 * Box-centre tracking is coarser than pose but plenty for running speed.
 */
class PersonTracker {

    private var lastTrackingId: Int? = null
    private var lastCenter: PointF? = null
    private var lastSeenSec = -1.0

    /** True when the current pick started a new track (speed history must reset). */
    var startedNewTrack = false
        private set

    fun pick(
        objects: List<DetectedObject>,
        /** Bounding box of the pose-tracked player in image coords, if any. */
        posePlayerBox: RectF?,
        tSec: Double,
    ): DetectedObject? {
        startedNewTrack = false

        val candidates = objects.filter { obj ->
            obj.labels.any { it.text.equals("Person", ignoreCase = true) } &&
                (posePlayerBox == null || overlapFraction(obj.boundingBox, posePlayerBox) < MAX_POSE_OVERLAP)
        }

        if (candidates.isEmpty()) {
            if (lastSeenSec >= 0 && tSec - lastSeenSec > TRACK_TIMEOUT_SECONDS) dropTrack()
            return null
        }

        val previousCenter = lastCenter
        val picked =
            candidates.firstOrNull { it.trackingId != null && it.trackingId == lastTrackingId }
                ?: previousCenter?.let { prev ->
                    candidates.minByOrNull { distance(center(it.boundingBox), prev) }
                }
                ?: candidates.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return null

        startedNewTrack = lastCenter == null ||
            picked.trackingId == null || picked.trackingId != lastTrackingId
        lastTrackingId = picked.trackingId
        lastCenter = center(picked.boundingBox)
        lastSeenSec = tSec
        return picked
    }

    private fun dropTrack() {
        lastTrackingId = null
        lastCenter = null
        lastSeenSec = -1.0
    }

    /** How much of [box] lies inside [other]. */
    private fun overlapFraction(box: Rect, other: RectF): Float {
        val w = max(0f, min(box.right.toFloat(), other.right) - max(box.left.toFloat(), other.left))
        val h = max(0f, min(box.bottom.toFloat(), other.bottom) - max(box.top.toFloat(), other.top))
        val area = box.width().toFloat() * box.height()
        return if (area <= 0f) 0f else w * h / area
    }

    private fun center(box: Rect) = PointF(box.exactCenterX(), box.exactCenterY())

    private fun distance(a: PointF, b: PointF) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private companion object {
        const val TRACK_TIMEOUT_SECONDS = 0.8
        const val MAX_POSE_OVERLAP = 0.4f
    }
}
