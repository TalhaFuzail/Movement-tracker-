package com.movementtracker.ar

import android.app.Activity
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.movementtracker.R
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Measures the distance from the phone to the playing surface with ARCore,
 * so the pixels-to-metres scale can be derived from the camera's real
 * intrinsics instead of manual taps or an assumed player height.
 *
 * Usage: point the phone at the ground where the player will be, wait for
 * the distance readout to lock, tap "Use". Returns distance + camera
 * intrinsics to MainActivity, which converts them into a view-space scale.
 */
class ArCalibrateActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var distanceText: TextView
    private lateinit var useButton: Button

    private var arSession: Session? = null
    private var installRequested = false
    private val background = CameraBackgroundRenderer()

    private var viewWidth = 1
    private var viewHeight = 1

    // Last stable measurement, written on the GL thread, read on the UI thread.
    @Volatile private var lastDistanceMeters = 0.0
    @Volatile private var lastFocalPx = 0.0
    @Volatile private var lastImageLongPx = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_calibrate)

        distanceText = findViewById(R.id.ar_distance)
        useButton = findViewById(R.id.btn_ar_use)
        useButton.isEnabled = false
        useButton.setOnClickListener { finishWithResult() }

        surfaceView = findViewById(R.id.ar_surface)
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        if (arSession == null && !createSession()) return
        try {
            arSession?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, R.string.ar_camera_busy, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }

    private fun createSession(): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    val session = Session(this)
                    session.configure(
                        Config(session).apply {
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            focusMode = Config.FocusMode.AUTO
                        }
                    )
                    arSession = session
                    true
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.ar_unavailable, Toast.LENGTH_LONG).show()
            finish()
            false
        }
    }

    // --- GLSurfaceView.Renderer -------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        android.opengl.GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        arSession?.setDisplayGeometry(display?.rotation ?: 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        android.opengl.GLES20.glClear(
            android.opengl.GLES20.GL_COLOR_BUFFER_BIT or android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
        )
        val session = arSession ?: return
        session.setCameraTextureName(background.textureId)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            return
        }
        background.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            postDistance(null)
            return
        }

        // Hit-test the screen centre against detected planes/depth points.
        val hit = frame.hitTest(viewWidth / 2f, viewHeight / 2f).firstOrNull()
        if (hit == null) {
            postDistance(null)
            return
        }

        val cam = camera.pose
        val hitPose = hit.hitPose
        val dx = cam.tx() - hitPose.tx()
        val dy = cam.ty() - hitPose.ty()
        val dz = cam.tz() - hitPose.tz()
        val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble())

        val intrinsics = camera.imageIntrinsics
        lastDistanceMeters = distance
        lastFocalPx = intrinsics.focalLength[0].toDouble()
        lastImageLongPx = maxOf(
            intrinsics.imageDimensions[0], intrinsics.imageDimensions[1],
        ).toDouble()
        postDistance(distance)
    }

    private fun postDistance(distance: Double?) {
        runOnUiThread {
            if (distance == null) {
                distanceText.text = getString(R.string.ar_searching)
                useButton.isEnabled = false
            } else {
                distanceText.text = getString(R.string.ar_distance_format, distance)
                useButton.isEnabled = true
            }
        }
    }

    private fun finishWithResult() {
        if (lastDistanceMeters <= 0 || lastFocalPx <= 0) return
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_DISTANCE_M, lastDistanceMeters)
                .putExtra(EXTRA_FOCAL_PX, lastFocalPx)
                .putExtra(EXTRA_IMAGE_LONG_PX, lastImageLongPx),
        )
        finish()
    }

    companion object {
        const val EXTRA_DISTANCE_M = "ar_distance_m"
        const val EXTRA_FOCAL_PX = "ar_focal_px"
        const val EXTRA_IMAGE_LONG_PX = "ar_image_long_px"
    }
}
