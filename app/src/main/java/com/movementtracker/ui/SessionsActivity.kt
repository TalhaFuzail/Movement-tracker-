package com.movementtracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import com.movementtracker.R
import com.movementtracker.session.ProgressStats
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists all recorded sessions, newest first. Tap opens the detail screen. */
class SessionsActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private var sessions: List<SessionRecord> = emptyList()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val result = runCatching {
                // Re-read from disk: after an activity recreation (rotation,
                // process death behind the picker) the cached list is empty.
                val json = JSONArray().also { arr ->
                    store.listAll().forEach { arr.put(it.toJson()) }
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toString(2).toByteArray())
                } ?: error("no stream")
            }
            Toast.makeText(
                this,
                if (result.isSuccess) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        store = SessionStore(this)
        findViewById<View>(R.id.btn_export).setOnClickListener {
            exportLauncher.launch(
                "movement-tracker-sessions-" +
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
            )
        }
        findViewById<View>(R.id.btn_compare).setOnClickListener { pickCompareSessions() }
    }

    /**
     * Side-by-side comparison: pick two sessions that have replay videos,
     * one after the other, then open them stacked in [CompareActivity].
     */
    private fun pickCompareSessions() {
        val idsWithVideo = store.sessionIdsWithVideo()
        val withVideo = sessions.filter { it.id in idsWithVideo }
        if (withVideo.size < 2) {
            Toast.makeText(this, R.string.compare_needs_videos, Toast.LENGTH_SHORT).show()
            return
        }
        val dateFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
        val labels = withVideo
            .map { dateFormat.format(Date(it.startedAtMillis)) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_pick_first)
            .setItems(labels) { _, first ->
                val rest = withVideo.filterIndexed { index, _ -> index != first }
                val restLabels = labels.filterIndexed { index, _ -> index != first }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.compare_pick_second)
                    .setItems(restLabels) { _, second ->
                        startActivity(
                            Intent(this, CompareActivity::class.java)
                                .putExtra(CompareActivity.EXTRA_SESSION_A, withVideo[first].id)
                                .putExtra(CompareActivity.EXTRA_SESSION_B, rest[second].id)
                        )
                    }
                    .show()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        sessions = store.listAll()
        showBests()
        findViewById<View>(R.id.sessions_hint).visibility =
            if (sessions.isEmpty()) View.GONE else View.VISIBLE

        val list = findViewById<ListView>(R.id.sessions_list)
        val empty = findViewById<TextView>(R.id.sessions_empty)
        empty.text = getString(R.string.sessions_empty)
        list.emptyView = empty

        val dateFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
        val rows = sessions.map { s ->
            val minutes = (s.durationSec / 60).toInt()
            val seconds = (s.durationSec % 60).toInt()
            getString(
                R.string.session_row_format,
                dateFormat.format(Date(s.startedAtMillis)),
                minutes, seconds,
                s.topSpeedKmh,
            )
        }
        list.adapter = ArrayAdapter(this, R.layout.item_session, rows)

        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, SessionDetailActivity::class.java)
                    .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessions[position].id)
            )
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(sessions[position])
            true
        }
    }

    private fun showBests() {
        val header = findViewById<TextView>(R.id.bests_header)
        val body = findViewById<TextView>(R.id.bests_body)
        val exportButton = findViewById<View>(R.id.btn_export)
        val compareButton = findViewById<View>(R.id.btn_compare)
        val idsWithVideo = store.sessionIdsWithVideo()
        compareButton.visibility =
            if (sessions.count { it.id in idsWithVideo } >= 2) View.VISIBLE
            else View.GONE
        val bests = ProgressStats.compute(sessions, System.currentTimeMillis())
        if (bests == null) {
            header.visibility = View.GONE
            body.visibility = View.GONE
            exportButton.visibility = View.GONE
            return
        }
        exportButton.visibility = View.VISIBLE
        val lines = buildList {
            add(getString(R.string.best_top_speed, bests.topSpeedKmh))
            if (bests.bestShotKmh > 0) add(getString(R.string.best_shot, bests.bestShotKmh))
            if (bests.bestBowlKmh > 0) add(getString(R.string.best_bowl, bests.bestBowlKmh))
            if (bests.bestJumpCm > 0) add(getString(R.string.best_jump, bests.bestJumpCm))
            add(
                getString(
                    R.string.bests_totals,
                    bests.sessionCount, bests.totalDistanceMeters / 1000.0,
                )
            )
            bests.trendPercent?.let { trend ->
                add(
                    if (trend >= 0) getString(R.string.trend_up, trend)
                    else getString(R.string.trend_down, -trend)
                )
            }
        }
        header.visibility = View.VISIBLE
        body.visibility = View.VISIBLE
        body.text = lines.joinToString("\n")
    }

    private fun confirmDelete(session: SessionRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_delete_title)
            .setPositiveButton(R.string.session_delete_confirm) { _, _ ->
                store.delete(session.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
