package com.movementtracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        store = SessionStore(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        sessions = store.listAll()
        showBests()

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
        val bests = ProgressStats.compute(sessions, System.currentTimeMillis())
        if (bests == null) {
            header.visibility = View.GONE
            body.visibility = View.GONE
            return
        }
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
