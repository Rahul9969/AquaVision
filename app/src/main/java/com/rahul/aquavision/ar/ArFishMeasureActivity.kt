package com.rahul.aquavision.ar

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
import com.rahul.aquavision.R
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import android.view.PixelCopy
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.SyncWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.widget.Button

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
    private lateinit var btnSave: Button
    private lateinit var measureOverlay: ArMeasureOverlay

    // Final mathematical results for saving
    private var finalLengthCm: Float = 0f
    private var finalVolumeCm3: Float = 0f
    private var finalWeightGrams: Float = 0f

    // Measurement state
    private var anchor1: Anchor? = null
    private var anchor2: Anchor? = null
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
        btnSave = findViewById(R.id.btn_save)
        measureOverlay = findViewById(R.id.measure_overlay)

        btnReset.setOnClickListener { resetMeasurement() }
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveMeasurementToDb() }

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
                                Log.d(TAG, "Depth API ENABLED")
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Log.d(TAG, "Depth API NOT supported, using hit-test only")
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

                    val camera = frame.camera
                    if (camera.trackingState == TrackingState.TRACKING) {
                        updateOverlayPositions(camera)
                    }
                } catch (_: Exception) {}
            }
        })

        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupTouchListener() {
        // Touch goes on the overlay (which is on top of the GL surface)
        measureOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event.x, event.y)
            }
            true
        }
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val frame = currentFrame ?: return
        val s = session ?: return

        if (anchor1 != null && anchor2 != null) return // Already measured

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

        // Create ARCore anchor at this 3D point
        val pose = Pose.makeTranslation(worldPoint[0], worldPoint[1], worldPoint[2])
        val anchor = try {
            s.createAnchor(pose)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor", e)
            return
        }

        if (anchor1 == null) {
            anchor1 = anchor
            runOnUiThread {
                instructionText.text = "✅ Head marked! Now tap the TAIL of the fish"
                measureOverlay.setPoint1(screenX, screenY) // initial placement
            }
            Log.d(TAG, "Anchor 1 created at: [${worldPoint[0]}, ${worldPoint[1]}, ${worldPoint[2]}]")
        } else {
            anchor2 = anchor
            Log.d(TAG, "Anchor 2 created at: [${worldPoint[0]}, ${worldPoint[1]}, ${worldPoint[2]}]")
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

    private fun updateOverlayPositions(camera: Camera) {
        val viewWidth = glSurfaceView.width.toFloat()
        val viewHeight = glSurfaceView.height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f) return

        val projMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)

        val viewProjMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)

        var screenP1: android.graphics.PointF? = null
        var screenP2: android.graphics.PointF? = null

        anchor1?.let { a1 ->
            if (a1.trackingState == TrackingState.TRACKING) {
                screenP1 = projectToScreen(a1.pose.translation, viewProjMatrix, viewWidth, viewHeight)
            }
        }

        anchor2?.let { a2 ->
            if (a2.trackingState == TrackingState.TRACKING) {
                screenP2 = projectToScreen(a2.pose.translation, viewProjMatrix, viewWidth, viewHeight)
            }
        }

        runOnUiThread {
            if (screenP1 != null) {
                measureOverlay.updatePoint1(screenP1!!.x, screenP1!!.y)
            }
            if (screenP2 != null) {
                measureOverlay.updatePoint2(screenP2!!.x, screenP2!!.y)
            }
        }
    }

    private fun projectToScreen(point3d: FloatArray, viewProj: FloatArray, width: Float, height: Float): android.graphics.PointF? {
        val vector4 = floatArrayOf(point3d[0], point3d[1], point3d[2], 1.0f)
        val result = FloatArray(4)
        android.opengl.Matrix.multiplyMV(result, 0, viewProj, 0, vector4, 0)

        if (result[3] <= 0) return null // Behind camera

        val w = result[3]
        val ndcX = result[0] / w
        val ndcY = result[1] / w

        val screenX = ((ndcX + 1.0f) / 2.0f) * width
        val screenY = ((1.0f - ndcY) / 2.0f) * height // Y is flipped in 2D
        return android.graphics.PointF(screenX, screenY)
    }

    private fun calculateAndDisplay() {
        val a1 = anchor1 ?: return
        val a2 = anchor2 ?: return
        
        val p1 = a1.pose.translation
        val p2 = a2.pose.translation

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

        // Store for saving
        finalLengthCm = lengthCm
        finalVolumeCm3 = volumeCm3
        finalWeightGrams = weightGrams

        Log.d(TAG, "=== MEASUREMENT RESULT ===")
        Log.d(TAG, "Length: %.1f cm".format(lengthCm))
        Log.d(TAG, "Width: %.1f cm".format(widthCm))
        Log.d(TAG, "Thickness: %.1f cm".format(thicknessCm))
        Log.d(TAG, "Volume: %.0f cm³".format(volumeCm3))
        Log.d(TAG, "Weight: %.2f kg".format(weightKg))

        runOnUiThread {
            instructionText.text = "✅ Measurement complete!"

            // Give the overlay the final length so it can display the label
            measureOverlay.setPoint2Length(lengthCm)

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
        anchor1?.detach()
        anchor2?.detach()
        anchor1 = null
        anchor2 = null
        instructionText.text = "Tap the HEAD of the fish to start measuring"
        measureOverlay.reset()
        resultCard.animate().alpha(0f).setDuration(200).withEndAction {
            resultCard.visibility = View.GONE
        }.start()
        btnSave.isEnabled = true
        btnSave.text = "Save Measurement"
    }

    private fun saveMeasurementToDb() {
        val appContext = applicationContext
        
        btnSave.isEnabled = false
        btnSave.text = "Saving..."

        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width == 0 || height == 0) {
            fallbackSave(appContext)
            return
        }

        val arBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        try {
            val handlerThread = android.os.HandlerThread("PixelCopier")
            handlerThread.start()
            PixelCopy.request(glSurfaceView, arBitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(compositeBitmap)
                    
                    canvas.drawBitmap(arBitmap, 0f, 0f, null)
                    measureOverlay.draw(canvas)
                    
                    finishSaving(appContext, compositeBitmap)
                } else {
                    Log.e(TAG, "PixelCopy failed: $copyResult")
                    fallbackSave(appContext)
                }
                handlerThread.quitSafely()
            }, android.os.Handler(handlerThread.looper))
        } catch (e: Exception) {
            e.printStackTrace()
            fallbackSave(appContext)
        }
    }

    private fun fallbackSave(appContext: android.content.Context) {
        val emptyBitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(emptyBitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)
        measureOverlay.draw(canvas)
        finishSaving(appContext, emptyBitmap)
    }

    private fun finishSaving(context: android.content.Context, combinedBitmap: Bitmap) {
        try {
            val filename = "ar_measure_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, filename)
            val out = FileOutputStream(file)
            combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush(); out.close()

            val methodLabel = if (depthSupported) "ARCore Depth API" else "ARCore HitTest"
            val details = "Method: $methodLabel;;;Est. Volume: ${String.format("%.0f", finalVolumeCm3)}cm3;;;Est. Weight: ${String.format("%.0f", finalWeightGrams)}g"
            
            val title = "Fishing Metrics - AR"

            val db = DatabaseHelper(context)
            db.insertLog(System.currentTimeMillis(), file.absolutePath, title, details, 0.0, 0.0, "AR Measurement", 1)

            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND, syncRequest)

            runOnUiThread {
                Toast.makeText(context, "Measurement Saved!", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = true
                btnSave.text = "Save Measurement"
            }
        }
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
