package com.movementtracker.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.RecordedEvent
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import com.movementtracker.session.SuggestionEngine
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full stats for one session: totals, event timeline, coaching suggestions. */
class SessionDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        val store = SessionStore(this)
        val session = id?.let { store.load(it) }
        if (session == null) {
            finish()
            return
        }

        if (store.videoFor(session.id) != null) {
            findViewById<View>(R.id.btn_replay).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(
                        Intent(this@SessionDetailActivity, ReplayActivity::class.java)
                            .putExtra(EXTRA_SESSION_ID, session.id)
                    )
                }
            }
        }

        findViewById<TextView>(R.id.detail_title).text =
            SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.getDefault())
                .format(Date(session.startedAtMillis))
        findViewById<TextView>(R.id.detail_stats).text = buildStats(session)
        findViewById<TextView>(R.id.detail_events).text = buildEvents(session)

        if (session.samples.size >= 2) {
            findViewById<TextView>(R.id.detail_chart_header).visibility = View.VISIBLE
            findViewById<SpeedChartView>(R.id.detail_chart).apply {
                visibility = View.VISIBLE
                setData(session.samples, session.events, session.durationSec)
            }
        }

        val suggestions = SuggestionEngine.suggestionsFor(session)
        if (suggestions.isNotEmpty()) {
            findViewById<TextView>(R.id.detail_suggestions_header).visibility = View.VISIBLE
            findViewById<TextView>(R.id.detail_suggestions).apply {
                visibility = View.VISIBLE
                text = suggestions.joinToString("\n\n") { "• $it" }
            }
        }
    }

    private fun buildStats(s: SessionRecord): String {
        val minutes = (s.durationSec / 60).toInt()
        val seconds = (s.durationSec % 60).toInt()
        val ballEvents = s.events.count { it.type != ActivityType.SPRINT }
        return listOf(
            getString(R.string.stat_duration, minutes, seconds),
            getString(R.string.stat_top_speed, s.topSpeedKmh),
            getString(R.string.stat_avg_speed, s.avgMovingKmh),
            getString(R.string.stat_distance, s.distanceMeters),
            getString(R.string.stat_sprints, s.sprintCount),
            getString(R.string.stat_ball_events, ballEvents),
        ).joinToString("\n")
    }

    private fun buildEvents(s: SessionRecord): String {
        if (s.events.isEmpty()) return getString(R.string.events_none)
        return s.events.joinToString("\n") { e -> formatEvent(e) }
    }

    private fun formatEvent(e: RecordedEvent): String {
        val time = String.format(Locale.US, "%d:%02d", (e.tOffsetSec / 60).toInt(), (e.tOffsetSec % 60).toInt())
        return when (e.type) {
            ActivityType.SPRINT ->
                getString(R.string.event_sprint, time, e.playerKmh, e.durationSec)
            ActivityType.SOCCER_SHOT ->
                getString(R.string.event_soccer_shot, time, e.peakBallKmh)
            ActivityType.CRICKET_BOWL ->
                getString(R.string.event_cricket_bowl, time, e.peakBallKmh)
            ActivityType.BALL_EVENT ->
                getString(R.string.event_ball, time, e.peakBallKmh)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
