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
 * Impact timestamps use [SystemClock.elapsedRealtime], the same boot-time
 * clock as camera frame timestamps, so they compare directly to the vision
 * pipeline and can confirm that a detected ball event was a real strike.
 */
class ImpactAudioDetector(
    /** Called from the audio thread with the impact time in seconds (boot clock). */
    private val onImpact: (tSec: Double) -> Unit,
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Requires RECORD_AUDIO to already be granted; otherwise does nothing. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
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
                running = false
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                running = false
                return@Thread
            }

            record.startRecording()
            val buffer = ShortArray(CHUNK_SAMPLES)
            var noiseFloor = 0.0
            var lastImpactSec = 0.0
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
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
            record.stop()
            record.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        const val CHUNK_SAMPLES = 512 // ~23 ms per RMS window
        const val SPIKE_RATIO = 6.0
        const val MIN_RMS = 900.0
        const val MIN_IMPACT_GAP_SEC = 0.3
    }
}
