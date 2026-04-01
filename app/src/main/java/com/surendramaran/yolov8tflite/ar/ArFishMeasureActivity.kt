package com.surendramaran.yolov8tflite.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.surendramaran.yolov8tflite.R
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ArFishMeasureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ArFishMeasure"
        private const val CAMERA_PERMISSION_CODE = 3001

        // Scientific constants
        private const val FISH_DENSITY_G_CM3 = 1.05f
        private const val LENGTH_TO_WIDTH_RATIO = 3.5f
        private const val WIDTH_TO_THICKNESS_RATIO = 1.4f
    }

    private var session: Session? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private val backgroundRenderer = ArBackgroundRenderer()

    // UI elements
    private lateinit var instructionText: TextView
    private lateinit var resultCard: CardView
    private lateinit var tvLength: TextView
    private lateinit var tvWidth: TextView
    private lateinit var tvThickness: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvWeight: TextView
    private lateinit var tvMethod: TextView
    private lateinit var btnReset: ImageButton
    private lateinit var btnBack: ImageButton

    // Measurement state
    private var point1: FloatArray? = null // [x, y, z] world coordinates
    private var point2: FloatArray? = null
    private var currentFrame: Frame? = null
    private var depthSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_measure)

        // Bind views
        glSurfaceView = findViewById(R.id.gl_surface_view)
        instructionText = findViewById(R.id.tv_instruction)
        resultCard = findViewById(R.id.result_card)
        tvLength = findViewById(R.id.tv_length)
        tvWidth = findViewById(R.id.tv_width)
        tvThickness = findViewById(R.id.tv_thickness)
        tvVolume = findViewById(R.id.tv_volume)
        tvWeight = findViewById(R.id.tv_weight)
        tvMethod = findViewById(R.id.tv_method)
        btnReset = findViewById(R.id.btn_reset)
        btnBack = findViewById(R.id.btn_back)

        btnReset.setOnClickListener { resetMeasurement() }
        btnBack.setOnClickListener { finish() }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        setupGlSurface()
        setupTouchListener()
    }

    private fun setupGlSurface() {
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)

        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
                backgroundRenderer.createOnGlThread()

                // Create ARCore session on GL thread
                try {
                    session = Session(this@ArFishMeasureActivity).apply {
                        val arConfig = Config(this).apply {
                            focusMode = Config.FocusMode.AUTO
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

                            depthSupported = isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                            depthMode = if (depthSupported) {
                                Log.d(TAG, "✅ Depth API ENABLED")
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Log.d(TAG, "⚠️ Depth API NOT supported, using hit-test only")
                                Config.DepthMode.DISABLED
                            }
                        }
                        configure(arConfig)
                        resume()
                    }

                    runOnUiThread {
                        val methodLabel = if (depthSupported) "Depth + HitTest" else "HitTest Only"
                        tvMethod.text = "Method: $methodLabel"
                    }

                    Log.d(TAG, "AR session created successfully")
                } catch (e: UnavailableArcoreNotInstalledException) {
                    runOnUiThread {
                        Toast.makeText(this@ArFishMeasureActivity,
                            "ARCore is not installed. Please install it from Play Store.",
                            Toast.LENGTH_LONG).show()
                        finish()
                    }
                } catch (e: UnavailableDeviceNotCompatibleException) {
                    runOnUiThread {
                        Toast.makeText(this@ArFishMeasureActivity,
                            "This device does not support ARCore.",
                            Toast.LENGTH_LONG).show()
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create AR session", e)
                    runOnUiThread {
                        Toast.makeText(this@ArFishMeasureActivity,
                            "AR initialization failed: ${e.message}",
                            Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
                session?.setDisplayGeometry(0, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                val s = session ?: return
                try {
                    s.setCameraTextureName(backgroundRenderer.textureId)
                    val frame = s.update()
                    backgroundRenderer.draw(frame)
                    currentFrame = frame
                } catch (_: Exception) {}
            }
        })

        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupTouchListener() {
        glSurfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event.x, event.y)
            }
            true
        }
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val frame = currentFrame ?: return
        val s = session ?: return

        if (point1 != null && point2 != null) return // Already measured

        // First try: depth-based 3D point
        var worldPoint = if (depthSupported) {
            getPointFromDepth(frame, screenX, screenY)
        } else null

        // Fallback: hit-test against detected planes
        if (worldPoint == null) {
            worldPoint = getPointFromHitTest(frame, screenX, screenY)
        }

        if (worldPoint == null) {
            runOnUiThread {
                Toast.makeText(this, "Could not get 3D point. Move phone slowly to build depth map.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (point1 == null) {
            point1 = worldPoint
            runOnUiThread {
                instructionText.text = "✅ Head marked! Now tap the TAIL of the fish"
            }
            Log.d(TAG, "Point 1: [${worldPoint[0]}, ${worldPoint[1]}, ${worldPoint[2]}]")
        } else {
            point2 = worldPoint
            Log.d(TAG, "Point 2: [${worldPoint[0]}, ${worldPoint[1]}, ${worldPoint[2]}]")
            calculateAndDisplay()
        }
    }

    /**
     * Get 3D world coordinates from ARCore Depth API.
     * Samples a 5×5 grid around the tap point and averages the valid depth readings.
     */
    private fun getPointFromDepth(frame: Frame, screenX: Float, screenY: Float): FloatArray? {
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            val depthW = depthImage.width
            val depthH = depthImage.height

            val camera = frame.camera
            val intrinsics = camera.imageIntrinsics
            val fx = intrinsics.focalLength[0]
            val fy = intrinsics.focalLength[1]
            val cx = intrinsics.principalPoint[0]
            val cy = intrinsics.principalPoint[1]
            val imgDims = intrinsics.imageDimensions

            // Map screen coordinates to depth image coordinates
            val viewW = glSurfaceView.width.toFloat()
            val viewH = glSurfaceView.height.toFloat()
            val depthU = (screenX / viewW * depthW).toInt().coerceIn(2, depthW - 3)
            val depthV = (screenY / viewH * depthH).toInt().coerceIn(2, depthH - 3)

            // Multi-point sampling: 5×5 grid around tap
            val buffer = depthImage.planes[0].buffer
            val rowStride = depthImage.planes[0].rowStride
            var depthSum = 0f
            var validCount = 0

            for (dy in -2..2) {
                for (dx in -2..2) {
                    val u = (depthU + dx).coerceIn(0, depthW - 1)
                    val v = (depthV + dy).coerceIn(0, depthH - 1)
                    val index = v * rowStride + u * 2
                    if (index >= 0 && index < buffer.limit() - 1) {
                        val depthMm = buffer.getShort(index).toInt() and 0xFFFF
                        if (depthMm > 0 && depthMm < 10000) { // Valid range: 0-10m
                            depthSum += depthMm
                            validCount++
                        }
                    }
                }
            }

            depthImage.close()

            if (validCount < 5) return null // Not enough valid depth readings

            val avgDepthM = (depthSum / validCount) / 1000f

            // Back-project to 3D using camera intrinsics
            // Map screen coords to image coords
            val imgX = screenX / viewW * imgDims[0]
            val imgY = screenY / viewH * imgDims[1]

            val worldX = (imgX - cx) * avgDepthM / fx
            val worldY = (imgY - cy) * avgDepthM / fy
            val worldZ = avgDepthM

            Log.d(TAG, "Depth point: depth=${avgDepthM}m, validSamples=$validCount/25")

            return floatArrayOf(worldX, worldY, worldZ)

        } catch (e: Exception) {
            Log.e(TAG, "Depth sampling failed: ${e.message}")
            return null
        }
    }

    /**
     * Fallback: Get 3D coordinates from ARCore hit-test against detected surfaces.
     */
    private fun getPointFromHitTest(frame: Frame, screenX: Float, screenY: Float): FloatArray? {
        try {
            val hits = frame.hitTest(screenX, screenY)
            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    val pose = hit.hitPose
                    return floatArrayOf(pose.tx(), pose.ty(), pose.tz())
                }
            }
            // If no plane hit, try any hit
            val firstHit = hits.firstOrNull()
            if (firstHit != null) {
                val pose = firstHit.hitPose
                return floatArrayOf(pose.tx(), pose.ty(), pose.tz())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hit-test failed: ${e.message}")
        }
        return null
    }

    private fun calculateAndDisplay() {
        val p1 = point1 ?: return
        val p2 = point2 ?: return

        // 3D Euclidean distance
        val dx = p2[0] - p1[0]
        val dy = p2[1] - p1[1]
        val dz = p2[2] - p1[2]
        val distanceM = sqrt(dx * dx + dy * dy + dz * dz)
        val lengthCm = distanceM * 100f

        // Morphometric estimation
        val widthCm = lengthCm / LENGTH_TO_WIDTH_RATIO
        val thicknessCm = widthCm / WIDTH_TO_THICKNESS_RATIO

        // Ellipsoid volume: V = (π/6) × L × W × T
        val volumeCm3 = (Math.PI / 6.0 * lengthCm * widthCm * thicknessCm).toFloat()

        // Weight from volume and density
        val weightGrams = volumeCm3 * FISH_DENSITY_G_CM3
        val weightKg = weightGrams / 1000f

        Log.d(TAG, "=== MEASUREMENT RESULT ===")
        Log.d(TAG, "Length: %.1f cm".format(lengthCm))
        Log.d(TAG, "Width: %.1f cm".format(widthCm))
        Log.d(TAG, "Thickness: %.1f cm".format(thicknessCm))
        Log.d(TAG, "Volume: %.0f cm³".format(volumeCm3))
        Log.d(TAG, "Weight: %.2f kg".format(weightKg))

        runOnUiThread {
            instructionText.text = "✅ Measurement complete!"

            tvLength.text = "%.1f cm".format(lengthCm)
            tvWidth.text = "%.1f cm".format(widthCm)
            tvThickness.text = "%.1f cm".format(thicknessCm)
            tvVolume.text = "%.0f cm³".format(volumeCm3)
            tvWeight.text = "%.2f kg".format(weightKg)

            resultCard.visibility = View.VISIBLE
            resultCard.alpha = 0f
            resultCard.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun resetMeasurement() {
        point1 = null
        point2 = null
        instructionText.text = "Tap the HEAD of the fish to start measuring"
        resultCard.animate().alpha(0f).setDuration(200).withEndAction {
            resultCard.visibility = View.GONE
        }.start()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && !hasCameraPermission()) {
            Toast.makeText(this, "Camera permission is required for AR measurement", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
