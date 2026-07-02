package com.movementtracker.analysis

import android.annotation.SuppressLint
import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Result of analysing a single camera frame. All coordinates are in the
 * upright (rotation-applied) image coordinate space, i.e. within
 * [0, imageWidth] x [0, imageHeight].
 */
data class FrameResult(
    val timestampSec: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Pose landmark positions keyed by [com.google.mlkit.vision.pose.PoseLandmark] type. */
    val landmarks: Map<Int, PointF>,
    /** All objects the generic detector found this frame (ball candidates). */
    val objects: List<DetectedObject>,
)

/**
 * Runs ML Kit pose detection (player) and object detection (ball candidates)
 * on every camera frame and reports both through [onResult].
 *
 * Both detectors run fully on-device; on a Pixel 8 this comfortably keeps up
 * with a 30 fps 720p analysis stream.
 */
class FrameAnalyzer(
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        val timestampSec = imageProxy.imageInfo.timestamp / 1e9

        val poseTask = poseDetector.process(input)
        val objectTask = objectDetector.process(input)

        Tasks.whenAllComplete(poseTask, objectTask).addOnCompleteListener {
            try {
                val pose: Pose? = if (poseTask.isSuccessful) poseTask.result else null
                val objects: List<DetectedObject> =
                    if (objectTask.isSuccessful) objectTask.result else emptyList()

                val landmarks = HashMap<Int, PointF>()
                pose?.allPoseLandmarks?.forEach { lm ->
                    if (lm.inFrameLikelihood > MIN_LANDMARK_CONFIDENCE) {
                        landmarks[lm.landmarkType] = lm.position
                    }
                }

                onResult(
                    FrameResult(
                        timestampSec = timestampSec,
                        imageWidth = uprightWidth,
                        imageHeight = uprightHeight,
                        landmarks = landmarks,
                        objects = objects,
                    )
                )
            } finally {
                imageProxy.close()
            }
        }
    }

    fun shutdown() {
        try {
            poseDetector.close()
            objectDetector.close()
        } catch (_: MlKitException) {
            // Ignore shutdown races; the process is going away anyway.
        }
    }

    private companion object {
        const val MIN_LANDMARK_CONFIDENCE = 0.5f
    }
}
