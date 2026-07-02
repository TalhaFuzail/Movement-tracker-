package com.movementtracker.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.movementtracker.R
import com.movementtracker.analysis.SlowMoAnalyzer
import com.movementtracker.session.ActivityType
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionStore
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Picks a slow-motion clip from the gallery and analyses it offline for
 * accurate fast-ball speeds. The result is saved as a normal session.
 */
class SlowMoActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button

    private val executor = Executors.newSingleThreadExecutor()

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) analyze(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slowmo)

        status = findViewById(R.id.slowmo_status)
        results = findViewById(R.id.slowmo_results)
        progress = findViewById(R.id.slowmo_progress)
        pickButton = findViewById(R.id.btn_pick_video)
        pickButton.setOnClickListener { pickVideo.launch("video/*") }
    }

    private fun analyze(uri: Uri) {
        pickButton.isEnabled = false
        results.text = ""
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.slowmo_analyzing)

        executor.execute {
            val record = SlowMoAnalyzer(this).analyze(uri) { frame, total ->
                runOnUiThread {
                    progress.max = total
                    progress.progress = frame
                    status.text = getString(R.string.slowmo_progress, frame, total)
                }
            }

            if (record != null) SessionStore(this).save(record)

            runOnUiThread {
                pickButton.isEnabled = true
                progress.visibility = View.GONE
                if (record == null) {
                    status.text = getString(R.string.slowmo_failed)
                } else {
                    status.text = getString(R.string.slowmo_done)
                    results.text = summarize(record)
                }
            }
        }
    }

    private fun summarize(r: SessionRecord): String {
        val ballEvents = r.events.filter { it.type != ActivityType.SPRINT }
        val topBall = ballEvents.maxOfOrNull { it.peakBallKmh }
        val lines = mutableListOf<String>()
        lines += getString(R.string.slowmo_clip_length, r.durationSec)
        if (topBall != null) {
            lines += getString(R.string.slowmo_top_ball, topBall)
            lines += String.format(
                Locale.US, "%d ball event(s): %s",
                ballEvents.size,
                ballEvents.joinToString(", ") {
                    String.format(Locale.US, "%.1f km/h", it.peakBallKmh)
                },
            )
        }
        if (r.topSpeedKmh > 1) {
            lines += getString(R.string.slowmo_top_player, r.topSpeedKmh)
        }
        lines += getString(R.string.slowmo_saved_note)
        return lines.joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
