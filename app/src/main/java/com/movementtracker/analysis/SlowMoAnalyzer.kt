package com.movementtracker.analysis

import android.content.Context
import android.graphics.PointF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.movementtracker.session.SessionRecord
import com.movementtracker.session.SessionRecorder

/**
 * Offline analysis of a recorded (slow-motion) video clip.
 *
 * The key to accurate fast-ball speeds is the *capture* frame rate: a Pixel 8
 * slow-mo clip is captured at 120/240 fps even though it plays back at 30.
 * Frames are therefore 1/240 s apart in real time, which is what makes a
 * 100 km/h kick measurable — at that spacing the ball only moves a few
 * centimetres per frame.
 *
 * Runs the same pose + ball pipeline as the live camera, frame by frame, and
 * produces a normal [SessionRecord] (source "slowmo"). Must be called on a
 * background thread; ML calls are awaited synchronously.
 */
class SlowMoAnalyzer(private val context: Context) {

    /** Null when the clip can't be read (or the OS is too old for frame access). */
    fun analyze(uri: Uri, onProgress: (frame: Int, total: Int) -> Unit): SessionRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        val retriever = MediaMetadataRetriever()
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        // Same labelled detector as the live camera, so BallTracker's
        // ball-class preference works here too instead of falling back to
        // shape heuristics for the whole clip.
        val objectDetector = ObjectDetection.getClient(
            CustomObjectDetectorOptions.Builder(
                LocalModel.Builder()
                    .setAssetFilePath("ball_labeler.tflite")
                    .build()
            )
                .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .setClassificationConfidenceThreshold(0.25f)
                .setMaxPerObjectLabelCount(3)
                .build()
        )

        try {
            retriever.setDataSource(context, uri)

            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: return null
            val durationSec = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)

            // Slow-mo clips carry the real sensor rate here; normal videos don't.
            val captureFps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
            val playbackFps = if (durationSec != null && durationSec > 0.1) {
                frameCount / durationSec
            } else null
            val fps = (captureFps ?: playbackFps ?: 30.0).coerceIn(1.0, 1000.0)

            val recorder = SessionRecorder(System.currentTimeMillis())
            val classifier = ActivityClassifier { tSec, type, peakBallKmh, playerKmh, extras ->
                recorder.addBallEvent(tSec, type, peakBallKmh, playerKmh, extras)
            }
            val calibration = CalibrationManager()
            val ballTracker = BallTracker()
            val playerSpeed = SpeedCalculator(
                windowSeconds = 0.3, smoothing = 0.35, maxSpeedMetersPerSecond = 15.0,
            )
            // Very short window: at 240 fps that is still ~15 frames of data,
            // and it preserves the kick's true peak instead of averaging it away.
            val ballSpeed = SpeedCalculator(
                windowSeconds = 0.06, smoothing = 0.7, maxSpeedMetersPerSecond = 62.0,
            )

            val total = frameCount.coerceAtMost(MAX_FRAMES)
            for (i in 0 until total) {
                val bitmap = try {
                    retriever.getFrameAtIndex(i) ?: continue
                } catch (_: Exception) {
                    break
                }
                val tSec = i / fps
                val input = InputImage.fromBitmap(bitmap, 0)

                val pose = runCatching { Tasks.await(poseDetector.process(input)) }.getOrNull()
                val objects = runCatching { Tasks.await(objectDetector.process(input)) }
                    .getOrNull() ?: emptyList()

                val landmarks = HashMap<Int, PointF>()
                pose?.allPoseLandmarks?.forEach { lm ->
                    if (lm.inFrameLikelihood > 0.5f) landmarks[lm.landmarkType] = lm.position
                }

                calibration.updateAuto(landmarks)
                val metersPerPixel = calibration.metersPerPixel

                var ballKmh: Double? = null
                var ballCenter: PointF? = null
                val ball = ballTracker.pick(objects, bitmap.width, bitmap.height, tSec)
                if (ball != null && metersPerPixel != null) {
                    if (ballTracker.startedNewTrack) ballSpeed.reset()
                    ballCenter = PointF(
                        ball.boundingBox.exactCenterX(),
                        ball.boundingBox.exactCenterY(),
                    )
                    ballSpeed.add(tSec, ballCenter, metersPerPixel)
                    // Peaks/episodes read the unsmoothed fit, same as live.
                    ballKmh = ballSpeed.rawKmPerHour
                }

                val leftHip = landmarks[PoseLandmark.LEFT_HIP]
                val rightHip = landmarks[PoseLandmark.RIGHT_HIP]
                if (leftHip != null && rightHip != null && metersPerPixel != null) {
                    val hip = PointF((leftHip.x + rightHip.x) / 2f, (leftHip.y + rightHip.y) / 2f)
                    playerSpeed.add(tSec, hip, metersPerPixel)
                    recorder.addPlayerSample(tSec, playerSpeed.kmPerHour)
                }

                classifier.update(
                    tSec = tSec,
                    landmarks = landmarks,
                    ballCenter = ballCenter,
                    ballKmh = ballKmh,
                    playerKmh = playerSpeed.kmPerHour,
                    metersPerPixel = metersPerPixel,
                )

                bitmap.recycle()
                if (i % PROGRESS_EVERY == 0) onProgress(i, total)
            }
            onProgress(total, total)

            return if (recorder.isEmpty) null else recorder.finish().copy(source = "slowmo")
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
            runCatching { poseDetector.close() }
            runCatching { objectDetector.close() }
        }
    }

    private companion object {
        /** ~15 s of 240 fps footage; keeps worst-case analysis time bounded. */
        const val MAX_FRAMES = 3600
        const val PROGRESS_EVERY = 12
    }
}
