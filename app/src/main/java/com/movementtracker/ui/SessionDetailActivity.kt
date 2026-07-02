package com.movementtracker.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.RecordedEvent
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import com.movementtracker.session.SuggestionEngine
import android.view.View
import java.io.File
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

        findViewById<View>(R.id.btn_share_card).setOnClickListener { shareCard(session, store) }

        findViewById<TextView>(R.id.detail_title).text =
            SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.getDefault())
                .format(Date(session.startedAtMillis))
        findViewById<TextView>(R.id.detail_stats).text = buildStats(session)
        findViewById<TextView>(R.id.detail_events).text = buildEvents(session)

        showPlacement(session)

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

    /**
     * Renders the session onto a share-card image (using a replay frame from
     * the best moment as the background when one exists) and hands it to the
     * system share sheet. Rendering and PNG encoding run off the main thread.
     */
    private fun shareCard(session: SessionRecord, store: SessionStore) {
        Thread {
            val file = runCatching {
                val frame = store.videoFor(session.id)?.let { grabBestFrame(it, session) }
                val card = ShareCardRenderer.render(this, session, frame)
                val dir = File(cacheDir, "share").apply { mkdirs() }
                File(dir, "session-${session.id}.png").apply {
                    outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    Toast.makeText(this, R.string.share_card_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(send, getString(R.string.share_chooser_title))
                )
            }
        }.start()
    }

    /** A video frame from the session's best ball event (or the start). */
    private fun grabBestFrame(video: File, session: SessionRecord): Bitmap? {
        val bestSec = session.events
            .filter { it.type != ActivityType.SPRINT }
            .maxByOrNull { it.peakBallKmh }
            ?.tOffsetSec ?: 0.0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.getFrameAtTime(
                (bestSec * 1_000_000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Shows the 3×3 placement heat grid when the session has target data. */
    private fun showPlacement(session: SessionRecord) {
        val zones = session.events.mapNotNull { it.extras["placementZone"]?.toInt() }
        if (zones.isEmpty()) return

        val counts = IntArray(9)
        zones.filter { it in 0..8 }.forEach { counts[it]++ }
        val onTarget = zones.count { it in 0..8 }

        findViewById<View>(R.id.detail_placement_header).visibility = View.VISIBLE
        findViewById<PlacementGridView>(R.id.detail_placement_grid).apply {
            visibility = View.VISIBLE
            setCounts(counts)
        }
        findViewById<TextView>(R.id.detail_placement_accuracy).apply {
            visibility = View.VISIBLE
            text = getString(
                R.string.placement_accuracy,
                onTarget, zones.size, onTarget * 100.0 / zones.size,
            )
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
        val impactSuffix =
            if (e.extras["impactConfirmed"] == 1.0) getString(R.string.event_impact_suffix) else ""
        val launchSuffix = e.extras["launchAngleDeg"]
            ?.let { getString(R.string.event_launch_suffix, it) } ?: ""
        val zoneSuffix = e.extras["placementZone"]?.let { zone ->
            val index = zone.toInt()
            if (index in 0..8) {
                getString(
                    R.string.event_zone_suffix,
                    resources.getStringArray(R.array.placement_zones)[index],
                )
            } else {
                getString(R.string.event_zone_miss)
            }
        } ?: ""
        return when (e.type) {
            ActivityType.SPRINT ->
                getString(R.string.event_sprint, time, e.playerKmh, e.durationSec)
            ActivityType.SOCCER_SHOT ->
                getString(R.string.event_soccer_shot, time, e.peakBallKmh)
            ActivityType.CRICKET_BOWL ->
                getString(R.string.event_cricket_bowl, time, e.peakBallKmh)
            ActivityType.JUMP ->
                getString(R.string.event_jump, time, (e.extras["heightM"] ?: 0.0) * 100)
            ActivityType.BALL_EVENT ->
                getString(R.string.event_ball, time, e.peakBallKmh)
        } + launchSuffix + zoneSuffix + impactSuffix
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val FILE_PROVIDER_AUTHORITY = "com.movementtracker.fileprovider"
    }
}
