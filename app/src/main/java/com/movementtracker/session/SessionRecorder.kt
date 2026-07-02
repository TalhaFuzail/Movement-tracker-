package com.movementtracker.session

import java.util.UUID
import kotlin.math.max

/**
 * Accumulates one live session's data while the camera runs: the speed
 * timeline, distance covered, sprints, and ball events. Produces an
 * immutable [SessionRecord] on [finish].
 *
 * All speed inputs are km/h; timestamps are the camera clock in seconds.
 */
class SessionRecorder(private val startedAtMillis: Long) {

    private var startTSec: Double? = null
    private var lastTSec: Double? = null

    private val samples = mutableListOf<SpeedSample>()
    private val events = mutableListOf<RecordedEvent>()

    private var topSpeedKmh = 0.0
    private var distanceMeters = 0.0
    private var movingTimeSec = 0.0
    private var speedTimeIntegral = 0.0

    private var sprintStartSec: Double? = null
    private var sprintPeakKmh = 0.0
    private var sprintCount = 0

    val isEmpty: Boolean get() = samples.isEmpty() && events.isEmpty()

    /**
     * Anchors t=0 at the first camera frame after the session starts, so
     * event offsets line up with the replay video (which also starts then) —
     * not with the first pose detection, which can be seconds later.
     */
    fun anchorStart(tSec: Double) {
        if (startTSec == null) startTSec = tSec
    }

    /** Feed one frame's player speed estimate. */
    fun addPlayerSample(tSec: Double, kmh: Double) {
        val start = startTSec ?: tSec.also { startTSec = it }
        val dt = lastTSec?.let { (tSec - it).coerceIn(0.0, MAX_FRAME_GAP_SEC) } ?: 0.0
        lastTSec = tSec

        topSpeedKmh = max(topSpeedKmh, kmh)

        if (kmh > MOVING_THRESHOLD_KMH) {
            distanceMeters += kmh / 3.6 * dt
            movingTimeSec += dt
            speedTimeIntegral += kmh * dt
        }

        val lastSampleT = samples.lastOrNull()?.tOffsetSec ?: -SAMPLE_INTERVAL_SEC
        if (tSec - start - lastSampleT >= SAMPLE_INTERVAL_SEC) {
            samples.add(SpeedSample(tSec - start, kmh))
        }

        updateSprintState(tSec, start, kmh)
    }

    /** Feed a completed ball episode (a kick/throw/bowl that was tracked). */
    fun addBallEvent(
        tSec: Double,
        type: ActivityType,
        peakBallKmh: Double,
        playerKmh: Double,
        extras: Map<String, Double> = emptyMap(),
    ) {
        val start = startTSec ?: tSec.also { startTSec = it }
        events.add(
            RecordedEvent(
                tOffsetSec = tSec - start,
                type = type,
                peakBallKmh = peakBallKmh,
                playerKmh = playerKmh,
                durationSec = 0.0,
                extras = extras,
            )
        )
    }

    /** Feed a detected vertical jump. */
    fun addJump(tSec: Double, heightM: Double) {
        val start = startTSec ?: tSec.also { startTSec = it }
        events.add(
            RecordedEvent(
                tOffsetSec = tSec - start,
                type = ActivityType.JUMP,
                peakBallKmh = 0.0,
                playerKmh = 0.0,
                durationSec = 0.0,
                extras = mapOf("heightM" to heightM),
            )
        )
    }

    private fun updateSprintState(tSec: Double, start: Double, kmh: Double) {
        if (kmh >= SPRINT_THRESHOLD_KMH) {
            if (sprintStartSec == null) sprintStartSec = tSec
            sprintPeakKmh = max(sprintPeakKmh, kmh)
        } else {
            val sprintStart = sprintStartSec
            if (sprintStart != null) {
                val duration = tSec - sprintStart
                if (duration >= MIN_SPRINT_DURATION_SEC) {
                    sprintCount++
                    events.add(
                        RecordedEvent(
                            tOffsetSec = sprintStart - start,
                            type = ActivityType.SPRINT,
                            peakBallKmh = 0.0,
                            playerKmh = sprintPeakKmh,
                            durationSec = duration,
                        )
                    )
                }
                sprintStartSec = null
                sprintPeakKmh = 0.0
            }
        }
    }

    fun finish(): SessionRecord {
        // Close an in-flight sprint so it isn't lost when the user taps Stop.
        lastTSec?.let { updateSprintState(it, startTSec ?: it, 0.0) }

        val duration = max(0.0, (lastTSec ?: startTSec ?: 0.0) - (startTSec ?: 0.0))
        val avgMoving = if (movingTimeSec > 0.5) speedTimeIntegral / movingTimeSec else 0.0
        return SessionRecord(
            id = UUID.randomUUID().toString().take(8),
            startedAtMillis = startedAtMillis,
            durationSec = duration,
            source = "live",
            topSpeedKmh = topSpeedKmh,
            avgMovingKmh = avgMoving,
            distanceMeters = distanceMeters,
            sprintCount = sprintCount,
            samples = samples.toList(),
            events = events.sortedBy { it.tOffsetSec },
        )
    }

    private companion object {
        /** Below this the player is considered standing (ML jitter, not motion). */
        const val MOVING_THRESHOLD_KMH = 2.5
        const val SPRINT_THRESHOLD_KMH = 14.0
        const val MIN_SPRINT_DURATION_SEC = 1.5
        const val SAMPLE_INTERVAL_SEC = 0.25
        const val MAX_FRAME_GAP_SEC = 0.5
    }
}
