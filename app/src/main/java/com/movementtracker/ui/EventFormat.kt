package com.movementtracker.ui

import android.content.Context
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.RecordedEvent
import java.util.Locale

/**
 * One source for how a recorded event is presented as a video cue — the
 * replay and side-by-side screens must label and cue the same event
 * identically or the two screens contradict each other.
 */
internal object EventFormat {

    /** Cue this far before the event so the approach is visible. */
    const val EVENT_LEAD_IN_SEC = 2.0

    fun timeLabel(tSec: Double): String =
        String.format(Locale.US, "%d:%02d", (tSec / 60).toInt(), (tSec % 60).toInt())

    /** Button label for an event cue, e.g. "1:32 Bowl 87 km/h". */
    fun cueLabel(context: Context, e: RecordedEvent): String {
        val time = timeLabel(e.tOffsetSec)
        return when (e.type) {
            ActivityType.SOCCER_SHOT ->
                context.getString(R.string.replay_event_shot, time, e.peakBallKmh)
            ActivityType.CRICKET_BOWL ->
                context.getString(R.string.replay_event_bowl, time, e.peakBallKmh)
            ActivityType.JUMP ->
                context.getString(
                    R.string.replay_event_jump, time, (e.extras["heightM"] ?: 0.0) * 100,
                )
            else -> context.getString(R.string.replay_event_ball, time, e.peakBallKmh)
        }
    }
}
