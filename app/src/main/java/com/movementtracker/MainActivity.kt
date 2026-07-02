package com.movementtracker

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.pose.PoseLandmark
import com.movementtracker.analysis.BallTracker
import com.movementtracker.analysis.CalibrationManager
import com.movementtracker.analysis.FrameAnalyzer
import com.movementtracker.analysis.FrameResult
import com.movementtracker.analysis.SpeedCalculator
import com.movementtracker.ui.OverlayView
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

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(result: FrameResult) {
        overlay.setSourceImageSize(result.imageWidth, result.imageHeight)

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
        }

        // --- Ball ---------------------------------------------------------
        val ball = ballTracker.pick(
            result.objects, result.imageWidth, result.imageHeight, result.timestampSec,
        )
        var viewBallBox: RectF? = null
        if (ball != null && metersPerPixel != null) {
            if (ballTracker.startedNewTrack) ballSpeed.reset()

            val box = ball.boundingBox
            val topLeft = overlay.imageToView(PointF(box.left.toFloat(), box.top.toFloat()))
            val bottomRight = overlay.imageToView(PointF(box.right.toFloat(), box.bottom.toFloat()))
            viewBallBox = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

            val center = PointF(viewBallBox.centerX(), viewBallBox.centerY())
            val kmh = ballSpeed.add(result.timestampSec, center, metersPerPixel) * 3.6
            peakBallKmh = max(peakBallKmh, kmh)
            lastBallSeenSec = result.timestampSec

            ballSpeedText.text = getString(R.string.ball_speed_format, kmh)
            peakBallText.text = getString(R.string.peak_ball_speed_format, peakBallKmh)
        } else if (lastBallSeenSec >= 0 &&
            result.timestampSec - lastBallSeenSec > BALL_DISPLAY_TIMEOUT_SEC
        ) {
            ballSpeedText.text = getString(R.string.ball_speed_none)
        }

        // --- Status -------------------------------------------------------
        calibrationStatusText.text = when {
            calibration.isManual -> getString(R.string.calibration_manual)
            calibration.isCalibrated -> getString(R.string.calibration_auto)
            else -> getString(R.string.calibration_none)
        }

        overlay.update(viewLandmarks, viewBallBox)
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
    }

    private companion object {
        const val BALL_DISPLAY_TIMEOUT_SEC = 0.5
    }
}
