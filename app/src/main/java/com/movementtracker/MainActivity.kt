package com.movementtracker

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.pose.PoseLandmark
import android.content.Intent
import com.movementtracker.analysis.ActivityClassifier
import com.movementtracker.analysis.BallTracker
import com.movementtracker.analysis.CalibrationManager
import com.movementtracker.analysis.FrameAnalyzer
import com.movementtracker.analysis.FrameResult
import com.movementtracker.analysis.ImpactAudioDetector
import com.movementtracker.analysis.SpeedCalculator
import com.movementtracker.ar.ArCalibrateActivity
import com.movementtracker.session.ActivityType
import com.movementtracker.session.DrillTracker
import com.movementtracker.session.SessionRecorder
import com.movementtracker.session.SessionStore
import com.movementtracker.ui.OverlayView
import com.movementtracker.ui.SessionsActivity
import com.movementtracker.ui.SlowMoActivity
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var playerSpeedText: TextView
    private lateinit var ballSpeedText: TextView
    private lateinit var peakBallText: TextView
    private lateinit var calibrationStatusText: TextView

    private lateinit var analysisExecutor: ExecutorService
    private var frameAnalyzer: FrameAnalyzer? = null

    // Player speed is smoothed over a longer window (running is sustained
    // motion); ball speed uses a short window so a kick's peak isn't averaged away.
    private val playerSpeed = SpeedCalculator(windowSeconds = 0.35, smoothing = 0.35)
    private val ballSpeed = SpeedCalculator(windowSeconds = 0.12, smoothing = 0.6)
    private val ballTracker = BallTracker()
    private val calibration = CalibrationManager()

    private var peakBallKmh = 0.0
    private var lastBallSeenSec = -1.0

    private lateinit var sessionStore: SessionStore
    private lateinit var sessionButton: Button
    private var sessionRecorder: SessionRecorder? = null

    // Replay video: recorded only while a session runs, kept only if saved.
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var pendingVideoFileName: String? = null
    private val activityClassifier = ActivityClassifier { tSec, type, peakBallKmh, playerKmh, extras ->
        // The camera timestamp and the audio detector share the boot clock,
        // so a mic spike near the event means the strike was actually heard.
        val enriched =
            if (kotlin.math.abs(tSec - lastImpactSoundSec) < IMPACT_MATCH_WINDOW_SEC) {
                extras + ("impactConfirmed" to 1.0)
            } else extras
        sessionRecorder?.addBallEvent(tSec, type, peakBallKmh, playerKmh, enriched)
        onDrillAttempt(peakBallKmh)
        announce(type, peakBallKmh)
    }

    // Impact sound detection, active only while a session is recording.
    @Volatile
    private var lastImpactSoundSec = -1000.0
    private val impactDetector = ImpactAudioDetector { tSec -> lastImpactSoundSec = tSec }

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && sessionRecorder != null) impactDetector.start()
        }

    // Voice announcements: speaks each ball speed so you don't have to walk
    // back to the phone between attempts.
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceEnabled = false
    private lateinit var voiceButton: Button

    // Drill mode: N attempts against a target ball speed, with audio feedback.
    private lateinit var drillStatusText: TextView
    private lateinit var drillButton: Button
    private var drill: DrillTracker? = null
    private var toneGenerator: ToneGenerator? = null

    /** AR measurement waiting to be converted once the analysis image size is known. */
    private var pendingArCalibration: DoubleArray? = null

    private val arCalibrate =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                pendingArCalibration = doubleArrayOf(
                    data.getDoubleExtra(ArCalibrateActivity.EXTRA_DISTANCE_M, 0.0),
                    data.getDoubleExtra(ArCalibrateActivity.EXTRA_FOCAL_PX, 0.0),
                    data.getDoubleExtra(ArCalibrateActivity.EXTRA_IMAGE_LONG_PX, 0.0),
                )
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlay = findViewById(R.id.overlay_view)
        playerSpeedText = findViewById(R.id.player_speed)
        ballSpeedText = findViewById(R.id.ball_speed)
        peakBallText = findViewById(R.id.peak_ball_speed)
        calibrationStatusText = findViewById(R.id.calibration_status)

        findViewById<Button>(R.id.btn_calibrate).setOnClickListener { startCalibration() }
        findViewById<Button>(R.id.btn_reset_peak).setOnClickListener {
            peakBallKmh = 0.0
            peakBallText.text = getString(R.string.peak_ball_speed_format, 0.0)
        }

        sessionStore = SessionStore(this)
        sessionButton = findViewById(R.id.btn_session)
        sessionButton.setOnClickListener { toggleSession() }
        findViewById<Button>(R.id.btn_sessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_slowmo).setOnClickListener {
            startActivity(Intent(this, SlowMoActivity::class.java))
        }
        findViewById<Button>(R.id.btn_ar_calibrate).setOnClickListener {
            arCalibrate.launch(Intent(this, ArCalibrateActivity::class.java))
        }
        drillStatusText = findViewById(R.id.drill_status)
        drillButton = findViewById(R.id.btn_drill)
        drillButton.setOnClickListener { toggleDrill() }

        voiceEnabled = getPreferences(MODE_PRIVATE).getBoolean(PREF_VOICE, false)
        voiceButton = findViewById(R.id.btn_voice)
        voiceButton.text =
            getString(if (voiceEnabled) R.string.btn_voice_on else R.string.btn_voice_off)
        voiceButton.setOnClickListener { toggleVoice() }
        if (voiceEnabled) initTts()

        overlay.onCalibrationPointsReady = { a, b -> promptCalibrationDistance(a, b) }

        analysisExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 720p keeps both ML models comfortably real-time on the Pixel 8.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = FrameAnalyzer { result ->
                runOnUiThread { onFrame(result) }
            }
            frameAnalyzer = analyzer
            analysis.setAnalyzer(analysisExecutor, analyzer)

            val video = VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.HD),
                        )
                    )
                    .build()
            )

            provider.unbindAll()
            // Not every device can run preview + analysis + video together;
            // replay recording is dropped first, live tracking never is.
            try {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, video,
                )
                videoCapture = video
            } catch (e: IllegalArgumentException) {
                videoCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(result: FrameResult) {
        overlay.setSourceImageSize(result.imageWidth, result.imageHeight)
        applyPendingArCalibration(result)

        // --- Player -------------------------------------------------------
        val viewLandmarks = result.landmarks.mapValues { (_, p) -> overlay.imageToView(p) }

        if (viewLandmarks.isNotEmpty()) {
            val ys = viewLandmarks.values.map { it.y }
            val personHeightPx = (ys.max() - ys.min())
            calibration.updateAuto(personHeightPx)
        }

        val metersPerPixel = calibration.metersPerPixel

        val leftHip = viewLandmarks[PoseLandmark.LEFT_HIP]
        val rightHip = viewLandmarks[PoseLandmark.RIGHT_HIP]
        if (leftHip != null && rightHip != null && metersPerPixel != null) {
            val hipCenter = PointF((leftHip.x + rightHip.x) / 2f, (leftHip.y + rightHip.y) / 2f)
            playerSpeed.add(result.timestampSec, hipCenter, metersPerPixel)
            playerSpeedText.text =
                getString(R.string.player_speed_format, playerSpeed.kmPerHour)
            sessionRecorder?.addPlayerSample(result.timestampSec, playerSpeed.kmPerHour)
        }

        // --- Ball ---------------------------------------------------------
        val ball = ballTracker.pick(
            result.objects, result.imageWidth, result.imageHeight, result.timestampSec,
        )
        var viewBallBox: RectF? = null
        var frameBallKmh: Double? = null
        if (ball != null && metersPerPixel != null) {
            if (ballTracker.startedNewTrack) ballSpeed.reset()

            val box = ball.boundingBox
            val topLeft = overlay.imageToView(PointF(box.left.toFloat(), box.top.toFloat()))
            val bottomRight = overlay.imageToView(PointF(box.right.toFloat(), box.bottom.toFloat()))
            viewBallBox = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

            val center = PointF(viewBallBox.centerX(), viewBallBox.centerY())
            val kmh = ballSpeed.add(result.timestampSec, center, metersPerPixel) * 3.6
            frameBallKmh = kmh
            peakBallKmh = max(peakBallKmh, kmh)
            lastBallSeenSec = result.timestampSec

            ballSpeedText.text = getString(R.string.ball_speed_format, kmh)
            peakBallText.text = getString(R.string.peak_ball_speed_format, peakBallKmh)
        } else if (lastBallSeenSec >= 0 &&
            result.timestampSec - lastBallSeenSec > BALL_DISPLAY_TIMEOUT_SEC
        ) {
            ballSpeedText.text = getString(R.string.ball_speed_none)
        }

        val ballViewCenter = viewBallBox?.let { PointF(it.centerX(), it.centerY()) }
        activityClassifier.update(
            tSec = result.timestampSec,
            landmarks = viewLandmarks,
            ballCenter = ballViewCenter,
            ballKmh = frameBallKmh,
            playerKmh = playerSpeed.kmPerHour,
            metersPerPixel = metersPerPixel,
        )

        // --- Status -------------------------------------------------------
        calibrationStatusText.text = when {
            calibration.isManual && calibration.isFromAr -> getString(R.string.calibration_ar)
            calibration.isManual -> getString(R.string.calibration_manual)
            calibration.isCalibrated -> getString(R.string.calibration_auto)
            else -> getString(R.string.calibration_none)
        }

        overlay.update(viewLandmarks, viewBallBox)
    }

    /**
     * Converts an AR distance measurement into a view-space metres-per-pixel
     * scale. At depth d the camera sees d/fx metres per sensor pixel; scaling
     * by the ratio of sensor to analysis resolution (along the long axis,
     * whose field of view both streams share) and then by the preview's
     * image-to-view zoom gives the scale our speed math runs in.
     */
    private fun applyPendingArCalibration(result: FrameResult) {
        val (distanceM, focalPx, arLongPx) = pendingArCalibration ?: return
        if (distanceM <= 0 || focalPx <= 0 || arLongPx <= 0) {
            pendingArCalibration = null
            return
        }
        val analysisLongPx = max(result.imageWidth, result.imageHeight).toDouble()
        val metersPerImagePixel = distanceM * arLongPx / (focalPx * analysisLongPx)

        val origin = overlay.imageToView(PointF(0f, 0f))
        val unit = overlay.imageToView(PointF(1f, 0f))
        val viewScale = (unit.x - origin.x).toDouble()
        if (viewScale <= 0) return  // overlay not laid out yet; retry next frame

        calibration.setManualMetersPerPixel(metersPerImagePixel / viewScale, fromAr = true)
        playerSpeed.reset()
        ballSpeed.reset()
        pendingArCalibration = null
        Toast.makeText(this, R.string.ar_calibration_applied, Toast.LENGTH_SHORT).show()
    }

    // --- Session recording ---------------------------------------------------

    private fun toggleSession() {
        val recorder = sessionRecorder
        if (recorder == null) {
            sessionRecorder = SessionRecorder(System.currentTimeMillis())
            sessionButton.text = getString(R.string.btn_session_stop)
            startReplayRecording()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                impactDetector.start()
            } else {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            Toast.makeText(this, R.string.session_started, Toast.LENGTH_SHORT).show()
        } else {
            sessionRecorder = null
            sessionButton.text = getString(R.string.btn_session_start)
            if (recorder.isEmpty) {
                pendingVideoFileName = null
                Toast.makeText(this, R.string.session_discarded_empty, Toast.LENGTH_SHORT).show()
            } else {
                val record = recorder.finish()
                pendingVideoFileName = "${record.startedAtMillis}-${record.id}.mp4"
                Thread { sessionStore.save(record) }.start()
                Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show()
            }
            activeRecording?.stop()
            activeRecording = null
            impactDetector.stop()
        }
    }

    private fun startReplayRecording() {
        val capture = videoCapture ?: return
        val temp = sessionStore.tempVideoFile().also { it.delete() }
        activeRecording = capture.output
            .prepareRecording(this, FileOutputOptions.Builder(temp).build())
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val name = pendingVideoFileName
                    pendingVideoFileName = null
                    if (!event.hasError() && name != null) {
                        Thread { sessionStore.finalizeVideo(name) }.start()
                    } else {
                        sessionStore.tempVideoFile().delete()
                    }
                }
            }
    }

    // --- Drill mode ----------------------------------------------------------

    private fun toggleDrill() {
        if (drill != null) {
            drill = null
            drillStatusText.visibility = android.view.View.GONE
            drillButton.text = getString(R.string.btn_drill)
            Toast.makeText(this, R.string.drill_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        val countInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.drill_count_hint)
        }
        val targetInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.drill_target_hint)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(countInput)
            addView(targetInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.drill_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.drill_start) { _, _ ->
                val count = countInput.text.toString().toIntOrNull() ?: 10
                val target = targetInput.text.toString().toDoubleOrNull() ?: 50.0
                drill = DrillTracker(count.coerceIn(1, 100), target.coerceAtLeast(1.0))
                drillButton.text = getString(R.string.btn_drill_stop)
                updateDrillStatus()
                Toast.makeText(this, R.string.drill_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onDrillAttempt(peakBallKmh: Double) {
        val activeDrill = drill ?: return
        val hit = activeDrill.record(peakBallKmh)
        beep(hit)
        updateDrillStatus()
        if (activeDrill.isComplete) {
            drill = null
            drillButton.text = getString(R.string.btn_drill)
            drillStatusText.visibility = android.view.View.GONE
            AlertDialog.Builder(this)
                .setTitle(R.string.drill_done_title)
                .setMessage(
                    getString(
                        R.string.drill_result_format,
                        activeDrill.hitCount, activeDrill.targetCount, activeDrill.targetKmh,
                        activeDrill.bestKmh, activeDrill.averageKmh,
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun updateDrillStatus() {
        val activeDrill = drill ?: return
        drillStatusText.visibility = android.view.View.VISIBLE
        drillStatusText.text = getString(
            R.string.drill_status_format,
            activeDrill.attemptCount, activeDrill.targetCount, activeDrill.bestKmh,
        )
    }

    private fun beep(hit: Boolean) {
        val generator = toneGenerator
            ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
        generator.startTone(
            if (hit) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP,
        )
    }

    // --- Voice announcements -------------------------------------------------

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_VOICE, voiceEnabled).apply()
        voiceButton.text =
            getString(if (voiceEnabled) R.string.btn_voice_on else R.string.btn_voice_off)
        if (voiceEnabled) initTts()
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    private fun announce(type: ActivityType, peakBallKmh: Double) {
        if (!voiceEnabled || !ttsReady) return
        val label = when (type) {
            ActivityType.SOCCER_SHOT -> getString(R.string.voice_shot)
            ActivityType.CRICKET_BOWL -> getString(R.string.voice_bowl)
            else -> getString(R.string.voice_ball)
        }
        val phrase = getString(R.string.voice_event_format, label, peakBallKmh)
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "ball_event")
    }

    // --- Manual calibration ------------------------------------------------

    private fun startCalibration() {
        overlay.calibrationMode = true
        Toast.makeText(this, R.string.calibration_instructions, Toast.LENGTH_LONG).show()
    }

    private fun promptCalibrationDistance(a: PointF, b: PointF) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.calibration_distance_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.calibration_dialog_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val meters = input.text.toString().toDoubleOrNull()
                val pixels = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                if (meters != null && meters > 0) {
                    calibration.setManual(meters, pixels)
                    playerSpeed.reset()
                    ballSpeed.reset()
                    Toast.makeText(this, R.string.calibration_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.calibration_invalid, Toast.LENGTH_SHORT).show()
                }
                overlay.calibrationMode = false
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                overlay.calibrationMode = false
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        frameAnalyzer?.shutdown()
        analysisExecutor.shutdown()
        impactDetector.stop()
        toneGenerator?.release()
        toneGenerator = null
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val BALL_DISPLAY_TIMEOUT_SEC = 0.5
        const val TONE_VOLUME = 80
        const val PREF_VOICE = "voice_announcements"
        const val IMPACT_MATCH_WINDOW_SEC = 2.5
    }
}
