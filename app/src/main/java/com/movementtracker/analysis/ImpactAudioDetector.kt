package com.movementtracker.analysis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens for the sharp sound of a ball impact (boot on ball, ball on bat
 * or pitch) — a spike in microphone RMS well above the rolling noise floor.
 * Impact timestamps use [SystemClock.elapsedRealtime]; the camera clock's
 * base is device-dependent, so the caller measures the offset between the
 * two from live frames and converts before comparing (see MainActivity).
 */
class ImpactAudioDetector(
    /** Called from the audio thread with the impact time in seconds (boot clock). */
    private val onImpact: (tSec: Double) -> Unit,
) {

    // Each start() gets its own flag so a stale worker from a previous
    // session can never be revived by a quick stop()/start() sequence.
    @Volatile
    private var activeFlag: java.util.concurrent.atomic.AtomicBoolean? = null

    /** Requires RECORD_AUDIO to already be granted; otherwise does nothing. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (activeFlag?.get() == true) return
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        activeFlag = running
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, 4096),
                )
            } catch (_: Exception) {
                running.set(false)
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                running.set(false)
                return@Thread
            }

            try {
                record.startRecording()
            } catch (_: IllegalStateException) {
                record.release()
                running.set(false)
                return@Thread
            }
            val buffer = ShortArray(CHUNK_SAMPLES)
            var noiseFloor = 0.0
            var lastImpactSec = 0.0
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break // mic error/stolen: stop instead of spinning
                if (read == 0) continue
                var energy = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    energy += s * s
                }
                val rms = sqrt(energy / read)
                val tSec = SystemClock.elapsedRealtime() / 1000.0

                if (noiseFloor > 0 &&
                    rms > noiseFloor * SPIKE_RATIO &&
                    rms > MIN_RMS &&
                    tSec - lastImpactSec > MIN_IMPACT_GAP_SEC
                ) {
                    lastImpactSec = tSec
                    onImpact(tSec)
                }
                // Slow-tracking floor so a spike doesn't raise its own threshold.
                noiseFloor =
                    if (noiseFloor == 0.0) rms else noiseFloor * 0.97 + rms * 0.03
            }
            runCatching { record.stop() }
            record.release()
        }.start()
    }

    fun stop() {
        activeFlag?.set(false)
        activeFlag = null
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        const val CHUNK_SAMPLES = 512 // ~23 ms per RMS window
        const val SPIKE_RATIO = 6.0
        const val MIN_RMS = 900.0
        const val MIN_IMPACT_GAP_SEC = 0.3
    }
}
