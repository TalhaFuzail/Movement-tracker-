package com.movementtracker.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import java.util.Locale
import kotlin.math.max

/**
 * Plays the video recorded during a session. Ball events are listed as
 * buttons that jump playback to just before the moment, and the player
 * speed measured at the current playback position is shown live —
 * watching your fastest kick teaches more than the number alone.
 */
class ReplayActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var speedText: TextView
    private var session: SessionRecord? = null

    private val handler = Handler(Looper.getMainLooper())
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedReadout()
            handler.postDelayed(this, SPEED_UPDATE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay)

        val store = SessionStore(this)
        val id = intent.getStringExtra(SessionDetailActivity.EXTRA_SESSION_ID)
        val session = id?.let { store.load(it) }
        val video = id?.let { store.videoFor(it) }
        if (session == null || video == null) {
            finish()
            return
        }
        this.session = session

        speedText = findViewById(R.id.replay_speed)
        videoView = findViewById(R.id.replay_video)
        videoView.setMediaController(MediaController(this).apply {
            setAnchorView(videoView)
        })
        videoView.setVideoPath(video.absolutePath)
        videoView.setOnPreparedListener { it.isLooping = false }
        videoView.start()

        buildEventButtons(session)
    }

    private fun buildEventButtons(session: SessionRecord) {
        val row = findViewById<LinearLayout>(R.id.replay_events)
        val events = session.events.filter { it.type != ActivityType.SPRINT }
        if (events.isEmpty()) {
            findViewById<View>(R.id.replay_events_scroll).visibility = View.GONE
            return
        }
        events.forEach { e ->
            val label = when (e.type) {
                ActivityType.SOCCER_SHOT -> R.string.replay_event_shot
                ActivityType.CRICKET_BOWL -> R.string.replay_event_bowl
                else -> R.string.replay_event_ball
            }
            val button = Button(this).apply {
                text = getString(label, formatTime(e.tOffsetSec), e.peakBallKmh)
                setOnClickListener {
                    // Jump slightly before the event so the approach is visible.
                    videoView.seekTo(
                        max(0.0, (e.tOffsetSec - EVENT_LEAD_IN_SEC) * 1000).toInt()
                    )
                    videoView.start()
                }
            }
            row.addView(button)
        }
    }

    private fun updateSpeedReadout() {
        val session = session ?: return
        if (!videoView.isPlaying && videoView.currentPosition == 0) return
        val tSec = videoView.currentPosition / 1000.0
        val sample = session.samples.minByOrNull { kotlin.math.abs(it.tOffsetSec - tSec) }
        speedText.text = if (sample != null && kotlin.math.abs(sample.tOffsetSec - tSec) < 1.0) {
            getString(R.string.player_speed_format, sample.playerKmh)
        } else {
            getString(R.string.player_speed_placeholder)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(speedTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(speedTicker)
        videoView.pause()
    }

    private fun formatTime(tSec: Double) =
        String.format(Locale.US, "%d:%02d", (tSec / 60).toInt(), (tSec % 60).toInt())

    private companion object {
        const val SPEED_UPDATE_MS = 200L
        const val EVENT_LEAD_IN_SEC = 2.0
    }
}
