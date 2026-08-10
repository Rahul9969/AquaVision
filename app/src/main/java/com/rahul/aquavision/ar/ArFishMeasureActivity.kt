package com.rahul.aquavision.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.rahul.aquavision.R
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * AR Fish Measurement Activity — rebuilt from scratch using the official
 * ARCore Raw Depth API patterns.
 *
 * Measurement Modes:
 *   1. Depth Mode (Raw Depth preferred): Single tap starts multi-frame depth scan.
 *      The [DepthProcessor] accumulates 4 frames of confidence-filtered point cloud
 *      data, then computes full 3D measurements (L/W/H/Volume/Weight).
 *   2. HitTest Mode (fallback): Two-tap point-to-point measurement using tracked
 *      anchors for length-only measurement.
 *
 * State Machine:
 *   IDLE → SCANNING (depth) → RESULT_READY → SAVED
 *   IDLE → POINT1_PLACED → POINT2_PLACED (hit-test) → RESULT_READY → SAVED
 *
 * References:
 *   https://github.com/google-ar/arcore-android-sdk/tree/main/samples/raw_depth_java
 */
class ArFishMeasureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ArFishMeasure"
        private const val CAMERA_PERMISSION_CODE = 3001

        /** Number of depth frames to accumulate before computing measurement. */
        private const val TARGET_FRAME_COUNT = 4

        /** Delay between depth frame acquisition attempts (ms). */
        private const val FRAME_ACQUIRE_DELAY_MS = 350L
    }

    // ── State Machine ────────────────────────────────────────────────────

    private enum class MeasureState {
        IDLE,
        SCANNING,        // Depth mode: accumulating frames
        POINT1_PLACED,   // HitTest mode: first anchor placed
        POINT2_PLACED,   // HitTest mode: second anchor placed
        RESULT_READY,    // Measurement complete, showing results
        SAVED            // Results saved to database
    }

    @Volatile
    private var state = MeasureState.IDLE

    // ── AR Core ──────────────────────────────────────────────────────────

    private var session: Session? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private val backgroundRenderer = ArBackgroundRenderer()
    private val depthProcessor = DepthProcessor()

    /** Thread-safe lock for accessing the current frame. */
    private val frameLock = Object()

    @Volatile
    private var currentFrame: Frame? = null

    private var installRequested = false
    private var lastTrackingState: TrackingState? = null

    // ── Hit-test Anchors ─────────────────────────────────────────────────

    private var anchor1: Anchor? = null
    private var anchor2: Anchor? = null

    // ── UI Elements ──────────────────────────────────────────────────────

    private lateinit var instructionText: TextView
    private lateinit var resultCard: CardView
    private lateinit var tvLength: TextView
    private lateinit var tvWidth: TextView
    private lateinit var tvThickness: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvWeight: TextView
    private lateinit var tvMethod: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvFrameStatus: TextView
    private lateinit var tvDepthQuality: TextView
    private lateinit var scanProgressBar: ProgressBar
    private lateinit var btnReset: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: Button
    private lateinit var measureOverlay: ArMeasureOverlay

    // ── Final Results ────────────────────────────────────────────────────

    private var finalResult: MeasurementResult? = null

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_measure)

        bindViews()
        setupListeners()

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }

        setupGlSurface()
    }

    private fun bindViews() {
        glSurfaceView = findViewById(R.id.gl_surface_view)
        instructionText = findViewById(R.id.tv_instruction)
        resultCard = findViewById(R.id.result_card)
        tvLength = findViewById(R.id.tv_length)
        tvWidth = findViewById(R.id.tv_width)
        tvThickness = findViewById(R.id.tv_thickness)
        tvVolume = findViewById(R.id.tv_volume)
        tvWeight = findViewById(R.id.tv_weight)
        tvMethod = findViewById(R.id.tv_method)
        tvTrackingStatus = findViewById(R.id.tv_tracking_status)
        tvFrameStatus = findViewById(R.id.tv_frame_status)
        tvDepthQuality = findViewById(R.id.tv_depth_quality)
        scanProgressBar = findViewById(R.id.scan_progress_bar)
        btnReset = findViewById(R.id.btn_reset)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        measureOverlay = findViewById(R.id.measure_overlay)
    }

    private fun setupListeners() {
        btnReset.setOnClickListener { resetMeasurement() }
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveMeasurementToDb() }

        // Touch handler on the overlay (sits on top of GL surface)
        measureOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event.x, event.y)
            }
            true
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // GL Surface
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGlSurface() {
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)

        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
                backgroundRenderer.createOnGlThread()
                Log.d(TAG, "GL surface created")
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.rotation ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                session?.setDisplayGeometry(rotation, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                val s = session ?: return
                try {
                    s.setCameraTextureName(backgroundRenderer.textureId)
                    val frame = s.update()
                    backgroundRenderer.draw(frame)

                    synchronized(frameLock) {
                        currentFrame = frame
                    }

                    val camera = frame.camera

                    // Update tracking status only on state change
                    if (camera.trackingState != lastTrackingState) {
                        lastTrackingState = camera.trackingState
                        runOnUiThread { updateTrackingStatus(camera.trackingState) }
                    }

                    // Update anchor overlay positions when tracking
                    if (camera.trackingState == TrackingState.TRACKING) {
                        updateOverlayPositions(camera)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AR frame processing error", e)
                }
            }
        })

        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    // ══════════════════════════════════════════════════════════════════════
    // Touch / Tap Handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleTap(screenX: Float, screenY: Float) {
        val frame = synchronized(frameLock) { currentFrame } ?: return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Toast.makeText(this, "Move the phone slowly to establish tracking", Toast.LENGTH_SHORT).show()
            return
        }

        when (state) {
            MeasureState.IDLE -> {
                if (depthProcessor.isDepthSupported) {
                    startDepthScan(frame, screenX, screenY)
                } else {
                    placeFirstAnchor(frame, screenX, screenY)
                }
            }
            MeasureState.POINT1_PLACED -> {
                placeSecondAnchor(frame, screenX, screenY)
            }
            MeasureState.RESULT_READY, MeasureState.SAVED -> {
                // Ignore taps when showing results — user must reset first
            }
            MeasureState.SCANNING, MeasureState.POINT2_PLACED -> {
                // Already in progress
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Depth Scan Mode
    // ══════════════════════════════════════════════════════════════════════

    private fun startDepthScan(frame: Frame, tapX: Float, tapY: Float) {
        val activeSession = session ?: return

        // Find the table plane at the tap point
        val tablePlaneY = depthProcessor.findTablePlaneY(activeSession, frame, tapX, tapY)
        if (tablePlaneY == null) {
            Toast.makeText(
                this,
                "No surface detected. Move the phone slowly to scan the area.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        state = MeasureState.SCANNING
        depthProcessor.reset()

        // UI updates
        measureOverlay.setPoint1(tapX, tapY)
        measureOverlay.startScanProgress()
        instructionText.text = "Scanning 3D point cloud…"
        scanProgressBar.visibility = View.VISIBLE
        tvFrameStatus.visibility = View.VISIBLE
        tvDepthQuality.visibility = View.VISIBLE
        tvFrameStatus.text = "Frame 0/$TARGET_FRAME_COUNT"
        tvDepthQuality.text = "● Acquiring…"
        tvDepthQuality.setTextColor(Color.parseColor("#FFD54F"))

        // Launch depth accumulation coroutine
        lifecycleScope.launch(Dispatchers.Default) {
            accumulateDepthFrames(tablePlaneY, tapX, tapY)
        }
    }

    /**
     * Accumulate depth data over multiple frames for improved accuracy.
     * Runs on Dispatchers.Default (background thread).
     */
    private suspend fun accumulateDepthFrames(tablePlaneY: Float, tapX: Float, tapY: Float) {
        var attempts = 0
        val maxAttempts = TARGET_FRAME_COUNT * 6  // Allow retries for stale/unavailable frames

        while (depthProcessor.frameCount < TARGET_FRAME_COUNT && attempts < maxAttempts) {
            attempts++

            val frame = synchronized(frameLock) { currentFrame }
            if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
                delay(FRAME_ACQUIRE_DELAY_MS)
                continue
            }

            val newPoints = depthProcessor.acquireAndProcessDepth(frame, tablePlaneY)

            if (newPoints > 0) {
                val frameNum = depthProcessor.frameCount
                withContext(Dispatchers.Main) {
                    tvFrameStatus.text = "Frame $frameNum/$TARGET_FRAME_COUNT"
                    measureOverlay.setFrameProgress(frameNum, TARGET_FRAME_COUNT)

                    // Update quality indicator
                    val quality = when {
                        depthProcessor.pointCount > 5000 -> {
                            tvDepthQuality.setTextColor(Color.parseColor("#69F0AE"))
                            "● Excellent"
                        }
                        depthProcessor.pointCount > 2000 -> {
                            tvDepthQuality.setTextColor(Color.parseColor("#FFD54F"))
                            "● Good"
                        }
                        else -> {
                            tvDepthQuality.setTextColor(Color.parseColor("#FF5252"))
                            "● Limited"
                        }
                    }
                    tvDepthQuality.text = quality
                }
            }

            delay(FRAME_ACQUIRE_DELAY_MS)
        }

        // ── Compute measurements ──
        val frame = synchronized(frameLock) { currentFrame }
        val camera = frame?.camera
        val result = depthProcessor.computeMeasurements(
            viewWidth = glSurfaceView.width.toFloat(),
            viewHeight = glSurfaceView.height.toFloat(),
            camera = camera
        )

        withContext(Dispatchers.Main) {
            if (result != null && result.pointCount >= 100) {
                finalResult = result
                state = MeasureState.RESULT_READY
                showDepthResult(result, tapX, tapY)
            } else {
                state = MeasureState.IDLE
                instructionText.text = getIdleInstruction()
                scanProgressBar.visibility = View.GONE
                tvFrameStatus.visibility = View.GONE
                tvDepthQuality.visibility = View.GONE
                measureOverlay.reset()
                Toast.makeText(
                    this@ArFishMeasureActivity,
                    "Could not isolate 3D object. Tap directly on the fish.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDepthResult(result: MeasurementResult, tapX: Float, tapY: Float) {
        scanProgressBar.visibility = View.GONE

        instructionText.text = "✅ 3D Scan Complete! (${result.pointCount} points, ${result.framesUsed} frames)"

        // Update overlay with bounding box
        result.boundingBoxScreen?.let { box ->
            measureOverlay.setBoundingBox(box.left, box.top, box.right, box.bottom)
            measureOverlay.setPoint2Length(result.lengthCm)
        }

        // Populate result card
        tvLength.text = "L: %.1f cm".format(result.lengthCm)
        tvWidth.text = "W: %.1f cm".format(result.widthCm)
        tvThickness.text = "H: %.1f cm".format(result.heightCm)
        tvVolume.text = "%.0f cm³".format(result.volumeCm3)
        tvWeight.text = "%.2f kg".format(result.weightGrams / 1000f)

        val methodLabel = when (result.method) {
            "RawDepth" -> "Raw Depth API (${result.framesUsed} frames)"
            "Depth" -> "Depth API (${result.framesUsed} frames)"
            else -> "HitTest"
        }
        tvMethod.text = "Method: $methodLabel"

        resultCard.visibility = View.VISIBLE
        resultCard.alpha = 0f
        resultCard.animate().alpha(1f).setDuration(300).start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // HitTest Mode (Fallback for non-depth devices)
    // ══════════════════════════════════════════════════════════════════════

    private fun placeFirstAnchor(frame: Frame, screenX: Float, screenY: Float) {
        val bestHit = findBestHit(frame, screenX, screenY)
        if (bestHit == null) {
            Toast.makeText(this, "No surface detected. Move the phone slowly.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            anchor1 = bestHit.createAnchor()
            state = MeasureState.POINT1_PLACED
            measureOverlay.setPoint1(screenX, screenY)
            instructionText.text = "Now tap the other end of the fish"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor1", e)
            Toast.makeText(this, "Failed to place point. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeSecondAnchor(frame: Frame, screenX: Float, screenY: Float) {
        val bestHit = findBestHit(frame, screenX, screenY)
        if (bestHit == null) {
            Toast.makeText(this, "No surface detected.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            anchor2 = bestHit.createAnchor()
            state = MeasureState.POINT2_PLACED

            val p1 = anchor1!!.pose.translation
            val p2 = anchor2!!.pose.translation
            val dx = p1[0] - p2[0]
            val dy = p1[1] - p2[1]
            val dz = p1[2] - p2[2]
            val distM = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            val distCm = distM * 100f

            finalResult = MeasurementResult(
                lengthCm = distCm,
                widthCm = 0f,
                heightCm = 0f,
                volumeCm3 = 0f,
                weightGrams = 0f,
                pointCount = 2,
                framesUsed = 1,
                method = "HitTest"
            )
            state = MeasureState.RESULT_READY

            measureOverlay.updatePoint2(screenX, screenY)
            measureOverlay.setPoint2Length(distCm)

            tvLength.text = "L: %.1f cm".format(distCm)
            tvWidth.text = "—"
            tvThickness.text = "—"
            tvVolume.text = "—"
            tvWeight.text = "—"
            tvMethod.text = "Method: HitTest (2-point)"

            instructionText.text = "✅ Measurement complete!"
            resultCard.visibility = View.VISIBLE
            resultCard.alpha = 0f
            resultCard.animate().alpha(1f).setDuration(300).start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor2", e)
            Toast.makeText(this, "Failed to place point. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findBestHit(frame: Frame, screenX: Float, screenY: Float): HitResult? {
        val hits = frame.hitTest(screenX, screenY)
        // Prefer plane hits
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                return hit
            }
        }
        return hits.firstOrNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay Position Updates
    // ══════════════════════════════════════════════════════════════════════

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
            screenP1?.let { p1 -> measureOverlay.updatePoint1(p1.x, p1.y) }
            screenP2?.let { p2 -> measureOverlay.updatePoint2(p2.x, p2.y) }
        }
    }

    private fun projectToScreen(
        point3d: FloatArray, viewProj: FloatArray, width: Float, height: Float
    ): android.graphics.PointF? {
        val vector4 = floatArrayOf(point3d[0], point3d[1], point3d[2], 1.0f)
        val result = FloatArray(4)
        android.opengl.Matrix.multiplyMV(result, 0, viewProj, 0, vector4, 0)

        if (result[3] <= 0) return null
        val w = result[3]
        val ndcX = result[0] / w
        val ndcY = result[1] / w

        val screenX = ((ndcX + 1.0f) / 2.0f) * width
        val screenY = ((1.0f - ndcY) / 2.0f) * height
        return android.graphics.PointF(screenX, screenY)
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun updateTrackingStatus(trackingState: TrackingState) {
        when (trackingState) {
            TrackingState.TRACKING -> {
                tvTrackingStatus.text = "● Tracking"
                tvTrackingStatus.setTextColor(Color.parseColor("#69F0AE"))
            }
            TrackingState.PAUSED -> {
                tvTrackingStatus.text = "● Limited — move slowly"
                tvTrackingStatus.setTextColor(Color.parseColor("#FFD54F"))
            }
            TrackingState.STOPPED -> {
                tvTrackingStatus.text = "● Stopped"
                tvTrackingStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        }
    }

    private fun getIdleInstruction(): String {
        return if (depthProcessor.isDepthSupported) {
            "Tap the fish to perform a 3D Mesh Scan"
        } else {
            "Tap the head of the fish to start measuring"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reset
    // ══════════════════════════════════════════════════════════════════════

    private fun resetMeasurement() {
        anchor1?.detach()
        anchor2?.detach()
        anchor1 = null
        anchor2 = null
        finalResult = null
        depthProcessor.reset()
        state = MeasureState.IDLE

        instructionText.text = getIdleInstruction()
        measureOverlay.reset()
        scanProgressBar.visibility = View.GONE
        tvFrameStatus.visibility = View.GONE
        tvDepthQuality.visibility = View.GONE

        resultCard.animate().alpha(0f).setDuration(200).withEndAction {
            resultCard.visibility = View.GONE
        }.start()

        btnSave.isEnabled = true
        btnSave.text = "💾  Save Measurement"
    }

    // ══════════════════════════════════════════════════════════════════════
    // Save to Database
    // ══════════════════════════════════════════════════════════════════════

    private fun saveMeasurementToDb() {
        val result = finalResult ?: return
        val appContext = applicationContext
        btnSave.isEnabled = false
        btnSave.text = "Saving…"

        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width <= 0 || height <= 0) {
            fallbackSave(appContext, result)
            return
        }

        try {
            val arBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val handlerThread = HandlerThread("PixelCopier")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)

            // Timeout fallback
            val timeoutRunnable = Runnable {
                Log.w(TAG, "PixelCopy timed out — using fallback")
                arBitmap.recycle()
                handlerThread.quitSafely()
                fallbackSave(appContext, result)
            }
            handler.postDelayed(timeoutRunnable, 5000)

            PixelCopy.request(glSurfaceView, arBitmap, { copyResult ->
                handler.removeCallbacks(timeoutRunnable)
                try {
                    if (copyResult == PixelCopy.SUCCESS) {
                        val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(compositeBitmap)
                        canvas.drawBitmap(arBitmap, 0f, 0f, null)
                        measureOverlay.draw(canvas)
                        finishSaving(appContext, compositeBitmap, result)
                    } else {
                        Log.e(TAG, "PixelCopy failed with code $copyResult")
                        fallbackSave(appContext, result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in PixelCopy callback", e)
                    fallbackSave(appContext, result)
                } finally {
                    arBitmap.recycle()
                    handlerThread.quitSafely()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate save", e)
            fallbackSave(appContext, result)
        }
    }

    private fun fallbackSave(appContext: android.content.Context, result: MeasurementResult) {
        val emptyBitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(emptyBitmap)
        canvas.drawColor(Color.DKGRAY)
        measureOverlay.draw(canvas)
        finishSaving(appContext, emptyBitmap, result)
    }

    private fun finishSaving(
        context: android.content.Context,
        bitmap: Bitmap,
        result: MeasurementResult
    ) {
        try {
            val filename = "ar_measure_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, filename)
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush(); out.close()

            val details = "Method: ${result.method};;;" +
                    "Est. Volume: ${"%.0f".format(result.volumeCm3)}cm3;;;" +
                    "Est. Weight: ${"%.0f".format(result.weightGrams)}g;;;" +
                    "Points: ${result.pointCount};;;" +
                    "Frames: ${result.framesUsed}"

            val title = "Fishing Metrics - AR"

            val db = DatabaseHelper(context)
            db.insertLog(
                System.currentTimeMillis(),
                file.absolutePath,
                title,
                details,
                0.0, 0.0,
                "AR Measurement",
                2
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest)

            state = MeasureState.SAVED

            runOnUiThread {
                Toast.makeText(context, "Measurement Saved!", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = true
                btnSave.text = "💾  Save Measurement"
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Permissions & Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) return

        // Check ARCore availability and prompt install if needed
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
        } catch (e: UnavailableException) {
            Toast.makeText(this, "ARCore is not available: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Create session if needed
        if (session == null) {
            try {
                session = Session(this).also { newSession ->
                    depthProcessor.configureSession(newSession)

                    val methodLabel = when {
                        depthProcessor.isRawDepthSupported -> "Raw Depth + Confidence"
                        depthProcessor.isDepthSupported -> "Depth (Smoothed)"
                        else -> "HitTest Only"
                    }
                    tvMethod.text = "Method: $methodLabel"
                    instructionText.text = getIdleInstruction()
                }

                Log.d(TAG, "AR session created successfully")
            } catch (e: UnavailableArcoreNotInstalledException) {
                Toast.makeText(this, "ARCore is not installed. Please install from Play Store.", Toast.LENGTH_LONG).show()
                finish()
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                Toast.makeText(this, "This device does not support ARCore.", Toast.LENGTH_LONG).show()
                finish()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                Toast.makeText(this, "AR initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        // Resume session
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Please restart the app.", Toast.LENGTH_LONG).show()
            session = null
            finish()
            return
        }

        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        anchor1?.detach()
        anchor2?.detach()
        backgroundRenderer.destroy()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && !hasCameraPermission()) {
            Toast.makeText(this, "Camera permission is required for AR measurement", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
