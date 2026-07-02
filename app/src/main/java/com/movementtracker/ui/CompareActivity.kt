package com.movementtracker.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Two session replays stacked for technique comparison — e.g. your best
 * bowl against today's. Tapping an event under either clip cues that clip
 * to just before the moment; "Play both" then runs them together so the
 * two actions can be watched side by side. Audio is muted (two overlapping
 * soundtracks help nobody).
 */
class CompareActivity : AppCompatActivity() {

    private lateinit var videoA: VideoView
    private lateinit var videoB: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        val store = SessionStore(this)
        val idA = intent.getStringExtra(EXTRA_SESSION_A)
        val idB = intent.getStringExtra(EXTRA_SESSION_B)
        val sessionA = idA?.let { store.load(it) }
        val sessionB = idB?.let { store.load(it) }
        val fileA = idA?.let { store.videoFor(it) }
        val fileB = idB?.let { store.videoFor(it) }
        if (sessionA == null || sessionB == null || fileA == null || fileB == null) {
            finish()
            return
        }

        videoA = setupPanel(
            R.id.compare_video_a, R.id.compare_label_a, R.id.compare_events_a, sessionA, fileA,
        )
        videoB = setupPanel(
            R.id.compare_video_b, R.id.compare_label_b, R.id.compare_events_b, sessionB, fileB,
        )

        findViewById<Button>(R.id.btn_play_both).setOnClickListener {
            videoA.start()
            videoB.start()
        }
        findViewById<Button>(R.id.btn_pause_both).setOnClickListener {
            videoA.pause()
            videoB.pause()
        }
    }

    private fun setupPanel(
        videoId: Int,
        labelId: Int,
        eventsId: Int,
        session: SessionRecord,
        file: File,
    ): VideoView {
        findViewById<TextView>(labelId).text = SimpleDateFormat(
            "EEE d MMM, HH:mm", Locale.getDefault(),
        ).format(Date(session.startedAtMillis))

        val video = findViewById<VideoView>(videoId)
        video.setVideoPath(file.absolutePath)
        video.setOnPreparedListener { player ->
            player.setVolume(0f, 0f)
            // Show the first frame instead of a black rectangle.
            video.seekTo(1)
        }

        val row = findViewById<LinearLayout>(eventsId)
        val events = session.events.filter { it.type != ActivityType.SPRINT }
        if (events.isEmpty()) {
            (row.parent as View).visibility = View.GONE
            return video
        }
        events.forEach { e ->
            val time = String.format(
                Locale.US, "%d:%02d", (e.tOffsetSec / 60).toInt(), (e.tOffsetSec % 60).toInt(),
            )
            val label = when (e.type) {
                ActivityType.SOCCER_SHOT ->
                    getString(R.string.replay_event_shot, time, e.peakBallKmh)
                ActivityType.CRICKET_BOWL ->
                    getString(R.string.replay_event_bowl, time, e.peakBallKmh)
                ActivityType.JUMP ->
                    getString(R.string.replay_event_jump, time, (e.extras["heightM"] ?: 0.0) * 100)
                else -> getString(R.string.replay_event_ball, time, e.peakBallKmh)
            }
            row.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    video.seekTo(max(0.0, (e.tOffsetSec - EVENT_LEAD_IN_SEC) * 1000).toInt())
                    video.pause()
                }
            })
        }
        return video
    }

    override fun onPause() {
        super.onPause()
        // Guarded: onPause also runs when onCreate bailed out via finish().
        if (::videoA.isInitialized) videoA.pause()
        if (::videoB.isInitialized) videoB.pause()
    }

    companion object {
        const val EXTRA_SESSION_A = "session_a"
        const val EXTRA_SESSION_B = "session_b"
        private const val EVENT_LEAD_IN_SEC = 2.0
    }
}
