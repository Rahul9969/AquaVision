package com.rahul.aquavision.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.rahul.aquavision.R
import com.rahul.aquavision.data.Constants
import com.rahul.aquavision.data.Constants.LABELS_PATH
import com.rahul.aquavision.data.Constants.MODEL_PATH
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.SpeciesRepository
import com.rahul.aquavision.data.SyncWorker
import com.rahul.aquavision.databinding.FragmentCameraBinding
import com.rahul.aquavision.ml.BoundingBox
import com.rahul.aquavision.ml.Detector
import com.rahul.aquavision.ml.DetectorCache
import com.rahul.aquavision.ml.segmentation.AnalysisResult
import com.rahul.aquavision.ml.segmentation.DrawImages
import com.rahul.aquavision.ml.segmentation.InstanceSegmentation
import com.rahul.aquavision.ml.segmentation.Success
import com.rahul.aquavision.ml.segmentation.utils.Utils
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rahul.aquavision.data.ProtectedSpeciesRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CameraFragment : Fragment(), Detector.DetectorListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // --- Models ---
    private var detector: Detector? = null      // High accuracy (Small) model for capture
    private var detectorNano: Detector? = null  // Fast (Nano) model for preview
    private var detectorEyes: Detector? = null

    // --- Segmentation pipeline (coin reference + fish mask) ---
    private var coinSegmentation: InstanceSegmentation? = null
    private var fishSegmentation: InstanceSegmentation? = null
    private lateinit var drawImages: DrawImages
    private val segmentationMutex = Mutex()
    private var lastAnalysisResults: List<AnalysisResult>? = null
    private var lastCoinScalePxPerCm: Float = 50.0f   // default fallback scale
    private var lastCoinDetected: Boolean = false

    private lateinit var cameraExecutor: ExecutorService
    private var isCameraRunning = true

    private lateinit var detectionAdapter: DetectionAdapter

    private lateinit var dbHelper: DatabaseHelper
    private var lastBitmap: Bitmap? = null
    private var lastResults: List<BoundingBox> = emptyList()
    private var lastEyeResults: List<BoundingBox> = emptyList()

    private var currentZoomRatio = 1.0f

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    // Variable to store pre-fetched location
    private var capturedLocation: Location? = null

    private val boxColors = listOf(
        Color.parseColor("#FF5722"), Color.parseColor("#2979FF"), Color.parseColor("#00C853"),
        Color.parseColor("#FFD600"), Color.parseColor("#AA00FF"), Color.parseColor("#E91E63"),
        Color.parseColor("#00BCD4"), Color.parseColor("#3E2723")
    )

    private val speciesColorMap = mutableMapOf<String, Int>()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startCrop(it) }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processGalleryImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            Toast.makeText(context, getString(R.string.crop_error_with_message, error?.message), Toast.LENGTH_SHORT).show()
        }
    }

    private val eyesListener = object : Detector.DetectorListener {
        override fun onEmptyDetect() {
            lastEyeResults = emptyList()
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = getString(R.string.eyes_count_default)
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    binding.overlay.setEyeResults(emptyList())
                    binding.loadingProgress.visibility = View.GONE

                    binding.tvFreshnessSummary.visibility = View.GONE
                    binding.freshnessProgress.visibility = View.GONE

                    updateTotalCount()
                }
            }
        }

        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
            lastEyeResults = boundingBoxes
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.eyesCountLabel.text = getString(R.string.eyes_count_label, boundingBoxes.size)
                    binding.eyesCountLabel.visibility = View.VISIBLE
                    binding.overlay.setEyeResults(boundingBoxes)
                    binding.loadingProgress.visibility = View.GONE

                    if (boundingBoxes.isNotEmpty()) {
                        val totalEyes = boundingBoxes.size
                        val freshCount = boundingBoxes.count { box ->
                            val label = box.clsName.lowercase()
                            !label.contains("non") && !label.contains("spoil")
                        }
                        val freshRatio = (freshCount.toFloat() / totalEyes) * 100

                        // UPDATED: "Approx Freshness"
                        binding.tvFreshnessSummary.text = "Approx Freshness: ${freshRatio.toInt()}% ($freshCount/$totalEyes)"
                        binding.freshnessProgress.progress = freshRatio.toInt()

                        val color = if(freshRatio > 75) Color.parseColor("#4CAF50")
                        else if(freshRatio > 40) Color.parseColor("#FF9800")
                        else Color.parseColor("#F44336")

                        binding.tvFreshnessSummary.setTextColor(color)
                        binding.freshnessProgress.setIndicatorColor(color)

                        binding.tvFreshnessSummary.visibility = View.VISIBLE
                        binding.freshnessProgress.visibility = View.VISIBLE
                    } else {
                        binding.tvFreshnessSummary.visibility = View.GONE
                        binding.freshnessProgress.visibility = View.GONE
                    }

                    updateTotalCount()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())
        // 3 threads: camera analysis + species/eyes model threads
        cameraExecutor = Executors.newFixedThreadPool(3)

        // Initialise OpenCV (required for VolumeCalculator contour ops)
        OpenCVLoader.initDebug()

        drawImages = DrawImages(requireContext())

        // Initialise segmentation models
        coinSegmentation = InstanceSegmentation(
            requireContext(), Constants.COIN_MODEL_PATH, null, "Coin", 5
        ) { Log.e(TAG, "Coin model error: $it") }

        fishSegmentation = InstanceSegmentation(
            requireContext(), Constants.SEG_MODEL_PATH, null, "Fish", 5
        ) { Log.e(TAG, "Fish seg error: $it") }

        val appContext = requireContext().applicationContext
        cameraExecutor.execute {
            // Use cached models — only loads once, rebinds listeners on revisit
            DetectorCache.getOrInit(
                appContext = appContext,
                modelPath = MODEL_PATH,
                labelsPath = LABELS_PATH,
                mainListener = this,
                eyesListener = eyesListener
            ) { main, nano, eyes ->
                detector = main
                detectorNano = nano
                detectorEyes = eyes
            }
        }

        setupRecyclerView()

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        // Setup Bottom Sheet Behavior for Detection Panel
        binding.detectionPanelContainer.visibility = View.VISIBLE
        binding.detectionPanel.visibility = View.VISIBLE
        bottomSheetBehavior = BottomSheetBehavior.from(binding.detectionPanel)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.skipCollapsed = true
        
        // Listen to drag-down events to optionally dismiss
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    // Only restart if the camera isn't currently running, to prevent buggy loops
                    if (!isCameraRunning) {
                        restartCameraPreview()
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
            }
        })

        bindListeners()

        // Subtle pulse animation on the capture FAB
        startFabPulse()
    }

    private fun startFabPulse() {
        val scaleX = android.animation.ObjectAnimator.ofFloat(binding.fab, "scaleX", 1f, 1.08f, 1f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(binding.fab, "scaleY", 1f, 1.08f, 1f)
        android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 1500
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (_binding != null && binding.fab.visibility == View.VISIBLE) {
                        start()
                    }
                }
            })
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isCameraRunning && binding.imagePreview.visibility == View.GONE) {
            restartCameraPreview()
        }
    }

    private fun setupRecyclerView() {
        detectionAdapter = DetectionAdapter()
        binding.detectionList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = detectionAdapter
        }
    }

    private fun bindListeners() {
        binding.apply {
            val scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val currentRatio = zoomState.zoomRatio
                    val delta = detector.scaleFactor
                    val newZoomRatio = currentRatio * delta
                    camera?.cameraControl?.setZoomRatio(newZoomRatio)
                    currentZoomRatio = newZoomRatio
                    val roundedZoom = String.format("%.1fx", newZoomRatio)
                    zoomLevel.text = roundedZoom
                    zoomLevel.visibility = View.VISIBLE
                    zoomLevel.removeCallbacks { zoomLevel.visibility = View.GONE }
                    zoomLevel.postDelayed({ if (_binding != null) zoomLevel.visibility = View.GONE }, 2000)
                    return true
                }
            })

            viewFinder.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }

            btnDialogSave.setOnClickListener {
                saveCurrentDetection()
                saveDialog.visibility = View.GONE
            }

            btnDialogDiscard.setOnClickListener {
                restartCameraPreview()
            }

            btnGallery.setOnClickListener {
                galleryLauncher.launch("image/*")
            }

            fab.setOnClickListener {
                if (binding.imagePreview.visibility != View.VISIBLE) {
                    // CAPTURE BUTTON PRESSED — live camera is running
                    isCameraRunning = false
                    cameraProvider?.unbindAll()

                    binding.fab.visibility = View.GONE
                    binding.btnGallery.visibility = View.GONE
                    binding.liveCountBadge.visibility = View.GONE

                    // --- START LOCATION PRE-FETCH ---
                    capturedLocation = null
                    prefetchLocation()
                    // --------------------------------

                    lastBitmap?.let { bmp ->
                        binding.imagePreview.setImageBitmap(bmp)
                        binding.imagePreview.visibility = View.VISIBLE
                        binding.viewFinder.visibility = View.INVISIBLE
                        binding.overlay.setImageDimensions(bmp.width, bmp.height)
                        binding.loadingProgress.visibility = View.VISIBLE

                        clearDetections()

                        // ── Run all 4 models in PARALLEL ──
                        cameraExecutor.execute { detector?.detect(bmp) }
                        cameraExecutor.execute { detectorEyes?.detect(bmp) }
                        // Coin + segmentation pipeline runs on a coroutine using the lifecycle scope
                        runSegmentationPipeline(bmp)
                    }

                    // Panel will slide up when onDetect() fires with results
                    slidePanelUp()

                } else {
                    // Already showing a captured image — restart camera
                    restartCameraPreview()
                }
            }
        }
    }

    private fun prefetchLocation() {
        val appContext = context?.applicationContext ?: return

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        capturedLocation = location
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Fallback biomass estimate using species lookup table when segmentation finds no fish. */
    private data class SpeciesBiomass(val name: String, val totalWeight: Double, val totalVolume: Double, val color: Int)

    private fun calculateBiomass(boxes: List<BoundingBox>) {
        if (boxes.isEmpty()) {
            binding.tvBiomassTitle.visibility = View.GONE
            binding.biomassListContainer.visibility = View.GONE
            return
        }
        binding.biomassListContainer.removeAllViews()
        val grouped = boxes.groupBy { it.clsName }
        var grandTotalWeight = 0.0
        var grandTotalVolume = 0.0
        val speciesStats = mutableListOf<SpeciesBiomass>()
        for ((species, sBoxes) in grouped) {
            val count = sBoxes.size
            val info = SpeciesRepository.getSpeciesInfo(species)
            grandTotalWeight += count * info.avgWeight
            grandTotalVolume += count * info.avgVolume
            speciesStats.add(SpeciesBiomass(species, count * info.avgWeight, count * info.avgVolume, speciesColorMap[species] ?: Color.GRAY))
        }
        val totalKg = grandTotalWeight / 1000.0
        val totalLiters = grandTotalVolume / 1000.0
        binding.tvBiomassTitle.text = "Biomass (est.): ${String.format("%.2f", totalKg)} kg | ${String.format("%.2f", totalLiters)} L"
        for (stat in speciesStats.sortedByDescending { it.totalWeight }) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
            }
            val infoText = TextView(requireContext()).apply {
                text = "${stat.name}: ${String.format("%.0f", stat.totalWeight)}g (est.) | ${String.format("%.0f", stat.totalVolume)} cm³ (est.)"
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val progressIndicator = com.google.android.material.progressindicator.LinearProgressIndicator(requireContext()).apply {
                trackCornerRadius = (4 * resources.displayMetrics.density).toInt()
                trackColor = Color.parseColor("#1E293B")
                trackThickness = (8 * resources.displayMetrics.density).toInt()
                setIndicatorColor(stat.color)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8 * resources.displayMetrics.density).toInt())
                progress = if (grandTotalWeight > 0) ((stat.totalWeight / grandTotalWeight) * 100).toInt() else 0
            }
            rowLayout.addView(infoText)
            rowLayout.addView(progressIndicator)
            binding.biomassListContainer.addView(rowLayout)
        }
        binding.tvBiomassTitle.visibility = View.VISIBLE
        binding.biomassListContainer.visibility = View.VISIBLE
    }

    private fun calculateSpeciesDistribution(boxes: List<BoundingBox>) {

        if (boxes.isEmpty()) {
            binding.tvSpeciesRatioTitle.visibility = View.GONE
            binding.cardSpeciesBar.visibility = View.GONE
            binding.speciesLegendGroup.visibility = View.GONE
            return
        }

        binding.speciesStackedBar.removeAllViews()
        binding.speciesLegendGroup.removeAllViews()

        val totalFish = boxes.size
        val grouped = boxes.groupBy { it.clsName }
        val sortedGrouped = grouped.toList().sortedByDescending { it.second.size }

        for ((species, speciesBoxes) in sortedGrouped) {
            val count = speciesBoxes.size
            val color = speciesColorMap[species] ?: Color.GRAY

            val segment = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            params.weight = count.toFloat()
            segment.layoutParams = params
            segment.setBackgroundColor(color)
            binding.speciesStackedBar.addView(segment)

            val chip = Chip(requireContext())
            val ratio = (count.toFloat() / totalFish) * 100
            chip.text = "$species: $count (${ratio.toInt()}%)"
            chip.chipBackgroundColor = ColorStateList.valueOf(color)
            chip.setTextColor(Color.WHITE)
            chip.isClickable = false
            chip.ensureAccessibleTouchTarget(0)
            binding.speciesLegendGroup.addView(chip)
        }

        binding.tvSpeciesRatioTitle.visibility = View.VISIBLE
        binding.cardSpeciesBar.visibility = View.VISIBLE
        binding.speciesLegendGroup.visibility = View.VISIBLE
    }

    // ──────────────────────────────────────────────────────────────────
    //  SEGMENTATION PIPELINE: coin reference + fish mask → real volume
    // ──────────────────────────────────────────────────────────────────
    private fun runSegmentationPipeline(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            segmentationMutex.withLock {
                var coinResults = emptyList<com.rahul.aquavision.ml.segmentation.SegmentationResult>()
                var activePxPerCm = 50.0f   // fallback if coin not found
                var coinFound = false

                // Step A: detect coin → derive pixelsPerCm
                try {
                    coinSegmentation?.invoke(
                        frame = bitmap,
                        smoothEdges = false,
                        onSuccess = { success ->
                            coinResults = success.results
                            if (coinResults.isNotEmpty()) {
                                val coinBox = coinResults.first().box
                                val widthPx  = coinBox.w * bitmap.width
                                val heightPx = coinBox.h * bitmap.height
                                val diameterPx = max(widthPx, heightPx)
                                activePxPerCm = diameterPx / 2.7f  // Indian ₹10 coin = 27mm = 2.7cm diameter
                                coinFound = true
                            }
                        },
                        onFailure = { Log.e(TAG, "Coin model failed: $it") }
                    )
                } catch (e: Exception) { Log.e(TAG, "Coin model exception", e) }

                lastCoinScalePxPerCm = activePxPerCm
                lastCoinDetected = coinFound

                // Step B: segment fish bodies
                var fishSuccess: Success? = null
                try {
                    fishSegmentation?.invoke(
                        frame = bitmap,
                        smoothEdges = false,
                        onSuccess = { success -> fishSuccess = success },
                        onFailure = { Log.e(TAG, "Fish seg model failed: $it") }
                    )
                } catch (e: Exception) { Log.e(TAG, "Fish seg exception", e) }

                val finalFish = fishSuccess ?: Success(0, 0, 0, emptyList())

                // Step C: draw overlays and compute per-fish measurements
                // We need lastResults (species boxes) — wait until they're available
                // (they arrive from onDetect which may have run already)
                val speciesBoxes = lastResults   // snapshot

                val analysisResults = drawImages.invoke(
                    original      = bitmap,
                    success       = finalFish,
                    coinResults   = coinResults,
                    isSeparateOut = false,
                    isMaskOut     = false,
                    speciesBoxes  = speciesBoxes,
                    pixelsPerCm   = activePxPerCm,
                    isMarkerDetected = coinFound
                )

                lastAnalysisResults = analysisResults

                // Update UI on main thread
                launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    displaySegmentationResults(analysisResults, coinFound, activePxPerCm, speciesBoxes)
                }
            }
        }
    }

    /**
     * Updates the Approx Biomass panel with real measured values from the segmentation pipeline.
     * Falls back to lookup-table estimates if segmentation found nothing.
     */
    private fun displaySegmentationResults(
        analysisResults: List<AnalysisResult>,
        coinDetected: Boolean,
        pxPerCm: Float,
        speciesBoxes: List<BoundingBox>
    ) {
        if (_binding == null) return
        if (analysisResults.isEmpty()) {
            // Segmentation found no fish — fall back to lookup-table
            calculateBiomass(lastResults)
            return
        }

        binding.biomassListContainer.removeAllViews()

        var grandTotalWeightG = 0.0
        var grandTotalVolumeCm3 = 0.0

        // Parse each fish result from the description string (produced by DrawImages)
        // Format: "Est. Weight: 450g\nVolume: 312 cm³\n..."
        val weightPattern = Regex("Est\\.\\s*Weight:\\s*([\\d.]+)g")
        val volumePattern  = Regex("Volume:\\s*([\\d.]+)")

        for ((idx, result) in analysisResults.withIndex()) {
            val desc = result.description
            val weightG   = weightPattern.find(desc)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val volumeCm3 = volumePattern.find(desc)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            grandTotalWeightG   += weightG
            grandTotalVolumeCm3 += volumeCm3

            // Species name for this fish
            val speciesName = if (speciesBoxes.isNotEmpty() && idx < speciesBoxes.size)
                speciesBoxes[idx].clsName else "Fish ${idx + 1}"
            val color = speciesColorMap[speciesName] ?: Color.parseColor("#22D3EE")

            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            }

            val weightKg   = weightG / 1000.0
            val volumeL    = volumeCm3 / 1000.0
            val measuredBy = if (coinDetected) "🪙 Coin ref. measured" else "⚠️ Est. (no coin)"
            val infoText = TextView(requireContext()).apply {
                text = "$speciesName: ${String.format("%.0f", weightG)}g (${String.format("%.0f", volumeCm3)} cm³)  ·  $measuredBy"
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
            }

            val progressIndicator = com.google.android.material.progressindicator.LinearProgressIndicator(requireContext()).apply {
                trackCornerRadius = (4 * resources.displayMetrics.density).toInt()
                trackColor        = Color.parseColor("#1E293B")
                trackThickness    = (8 * resources.displayMetrics.density).toInt()
                setIndicatorColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (8 * resources.displayMetrics.density).toInt()
                )
                progress = ((weightG / (grandTotalWeightG + 1.0)) * 100).toInt().coerceIn(1, 100)
            }

            rowLayout.addView(infoText)
            rowLayout.addView(progressIndicator)
            binding.biomassListContainer.addView(rowLayout)
        }

        val totalKg    = grandTotalWeightG / 1000.0
        val totalLiters = grandTotalVolumeCm3 / 1000.0
        val scaleInfo   = if (coinDetected)
            "${String.format("%.1f", pxPerCm)} px/cm (coin)"
        else
            "est. scale"
        binding.tvBiomassTitle.text =
            "Biomass: ${String.format("%.3f", totalKg)} kg | ${String.format("%.3f", totalLiters)} L  [$scaleInfo]"

        binding.tvBiomassTitle.visibility   = View.VISIBLE
        binding.biomassListContainer.visibility = View.VISIBLE
    }

    private fun showPausedState() {
        binding.imagePreview.visibility = View.VISIBLE
        binding.viewFinder.visibility = View.INVISIBLE
        binding.saveDialog.visibility = View.GONE
        
        binding.fab.visibility = View.VISIBLE
        binding.btnGallery.visibility = View.VISIBLE


        slidePanelDown()

        lastBitmap?.let { binding.overlay.setImageDimensions(it.width, it.height) }
        isCameraRunning = false
    }

    private fun startCrop(sourceUri: Uri) {
        try {
            val destFile = File(requireContext().cacheDir, "cropped_cam_${System.currentTimeMillis()}.jpg")
            val destUri = Uri.fromFile(destFile)
            val options = UCrop.Options()
            options.setToolbarTitle(getString(R.string.crop_for_ai))
            options.setFreeStyleCropEnabled(true)
            val uCrop = UCrop.of(sourceUri, destUri).withOptions(options)
            cropImage.launch(uCrop.getIntent(requireContext()))
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_starting_crop), e)
        }
    }

    private fun processGalleryImage(uri: Uri) {
        try {
            cameraProvider?.unbindAll()
            isCameraRunning = false
            binding.fab.setImageResource(android.R.drawable.ic_media_play)

            val inputStream = requireContext().contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)

            if (bitmap != null) {
                bitmap = Utils.rotateImageIfRequired(requireContext(), bitmap, uri)
                bitmap = Utils.resizeBitmap(bitmap, 640)

                lastBitmap = bitmap

                binding.viewFinder.visibility = View.INVISIBLE
                binding.imagePreview.visibility = View.VISIBLE
                binding.imagePreview.setImageBitmap(bitmap)
                binding.overlay.setImageDimensions(bitmap.width, bitmap.height)
                binding.saveDialog.visibility = View.VISIBLE
                binding.loadingProgress.visibility = View.VISIBLE

                binding.fab.visibility = View.GONE
                binding.btnGallery.visibility = View.GONE
                binding.liveCountBadge.visibility = View.GONE

                slidePanelUp()

                // START PRE-FETCH FOR GALLERY TOO
                capturedLocation = null
                prefetchLocation()

                clearDetections()

                // ── Run both models in PARALLEL ──
                cameraExecutor.execute { detector?.detect(bitmap) }
                cameraExecutor.execute { detectorEyes?.detect(bitmap) }
                runSegmentationPipeline(bitmap)
            } else {
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.error_loading_gallery_image), e)
            Toast.makeText(context, getString(R.string.error_loading_gallery_image), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearDetections() {
        lastResults = emptyList()
        lastEyeResults = emptyList()
        binding.overlay.clear()
        detectionAdapter.updateDetections(emptyList())
        binding.totalCountLabel.text = getString(R.string.total_detected, 0)
        binding.eyesCountLabel.visibility = View.GONE
        binding.noDetectionText.visibility = View.GONE

        binding.tvFreshnessSummary.visibility = View.GONE
        binding.freshnessProgress.visibility = View.GONE
        binding.tvSpeciesRatioTitle.visibility = View.GONE
        binding.cardSpeciesBar.visibility = View.GONE
        binding.speciesLegendGroup.visibility = View.GONE

        binding.tvBiomassTitle.visibility = View.GONE
        binding.biomassListContainer.visibility = View.GONE
    }

    private fun restartCameraPreview() {
        binding.imagePreview.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        binding.saveDialog.visibility = View.GONE
        binding.zoomLevel.visibility = View.GONE
        binding.eyesCountLabel.visibility = View.GONE
        binding.loadingProgress.visibility = View.GONE

        binding.tvFreshnessSummary.visibility = View.GONE
        binding.freshnessProgress.visibility = View.GONE
        binding.tvSpeciesRatioTitle.visibility = View.GONE
        binding.cardSpeciesBar.visibility = View.GONE
        binding.speciesLegendGroup.visibility = View.GONE

        binding.tvBiomassTitle.visibility = View.GONE
        binding.biomassListContainer.visibility = View.GONE

        slidePanelDown()

        binding.fab.visibility = View.VISIBLE
        binding.fab.setImageResource(R.drawable.ic_camera)
        binding.btnGallery.visibility = View.VISIBLE


        binding.overlay.setCameraMode()
        binding.overlay.clear()

        lastResults = emptyList()
        lastEyeResults = emptyList()
        detectionAdapter.updateDetections(emptyList())
        binding.totalCountLabel.text = getString(R.string.total_detected, 0)
        binding.noDetectionText.visibility = View.GONE

        startCamera()
        isCameraRunning = true
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException(getString(R.string.camera_init_failed))
        val rotation = view?.display?.rotation ?: Surface.ROTATION_0
        val viewWidth = binding.viewFinder.width
        val viewHeight = binding.viewFinder.height
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetRotation(rotation).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            val croppedBitmap = cropBitmapToView(rotatedBitmap, viewWidth, viewHeight)
            lastBitmap = croppedBitmap

            if (isCameraRunning) {
                // Use Nano Detector for Real-time Preview
                detectorNano?.detect(croppedBitmap)
            }
        }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalyzer)
            camera?.cameraControl?.setZoomRatio(currentZoomRatio)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, getString(R.string.use_case_binding_failed), exc)
        }
    }

    private fun cropBitmapToView(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): Bitmap {
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        if (viewWidth == 0 || viewHeight == 0) return bitmap
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val viewRatio = viewWidth.toFloat() / viewHeight
        var cropX = 0; var cropY = 0; var cropWidth = bitmapWidth; var cropHeight = bitmapHeight
        if (bitmapRatio > viewRatio) {
            cropHeight = bitmapHeight; cropWidth = (bitmapHeight * viewRatio).toInt(); cropX = (bitmapWidth - cropWidth) / 2
        } else {
            cropWidth = bitmapWidth; cropHeight = (bitmapWidth / viewRatio).toInt(); cropY = (bitmapHeight - cropHeight) / 2
        }
        if (cropWidth <= 0) cropWidth = 1; if (cropHeight <= 0) cropHeight = 1; if (cropX < 0) cropX = 0; if (cropY < 0) cropY = 0
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    }

    private fun saveCurrentDetection() {
        val bitmapToSave = lastBitmap ?: return
        val resultsToSave = lastResults
        val eyesToSave = lastEyeResults

        val appContext = requireContext().applicationContext

        // Check if pre-fetched location is available
        if (capturedLocation != null) {
            saveDetectionToDb(appContext, bitmapToSave, resultsToSave, eyesToSave, capturedLocation!!.latitude, capturedLocation!!.longitude, "Lat: ${capturedLocation!!.latitude}, Lng: ${capturedLocation!!.longitude}")
            return
        }

        // Fallback to normal fetch
        Toast.makeText(appContext, "Acquiring GPS...", Toast.LENGTH_SHORT).show()

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    var currentLat = 0.0
                    var currentLng = 0.0
                    var placeName = appContext.getString(R.string.location_not_available)

                    if (location != null) {
                        currentLat = location.latitude
                        currentLng = location.longitude
                        try {
                            val geocoder = Geocoder(appContext, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                            if (!addresses.isNullOrEmpty()) {
                                placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                            } else {
                                placeName = appContext.getString(R.string.lat_lng_location, currentLat, currentLng)
                            }
                        } catch (e: Exception) {
                            placeName = appContext.getString(R.string.lat_lng_location, currentLat, currentLng)
                        }
                    }
                    saveDetectionToDb(appContext, bitmapToSave, resultsToSave, eyesToSave, currentLat, currentLng, placeName)
                }
                .addOnFailureListener {
                    saveDetectionToDb(appContext, bitmapToSave, resultsToSave, eyesToSave, 0.0, 0.0, appContext.getString(R.string.location_not_available))
                }
        } else {
            saveDetectionToDb(appContext, bitmapToSave, resultsToSave, eyesToSave, 0.0, 0.0, appContext.getString(R.string.location_not_available))
        }
    }

    private fun saveDetectionToDb(context: Context, bitmapToSave: Bitmap, resultsToSave: List<BoundingBox>, eyesToSave: List<BoundingBox>, lat: Double, lng: Double, placeName: String) {
        try {
            val mutableBitmap = bitmapToSave.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f }
            val eyePaint = Paint().apply { color = ContextCompat.getColor(context, R.color.overlay_red); style = Paint.Style.STROKE; strokeWidth = 8f }
            val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
            val textBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

            resultsToSave.forEachIndexed { index, box ->
                val color = speciesColorMap.getOrPut(box.clsName) {
                    boxColors[speciesColorMap.size % boxColors.size]
                }
                boxPaint.color = color
                val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
                canvas.drawRect(left, top, right, bottom, boxPaint)
                val text = "${box.clsName} ${String.format("%.2f", box.cnf)}"
                val bounds = Rect(); textPaint.getTextBounds(text, 0, text.length, bounds)
                canvas.drawRect(left, top, left + bounds.width() + 16, top + bounds.height() + 16, textBgPaint)
                canvas.drawText(text, left, top + bounds.height(), textPaint)
            }

            eyesToSave.forEach { box ->
                val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height
                val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
                canvas.drawRect(left, top, right, bottom, eyePaint)
            }

            val filename = "fish_detect_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, filename)
            val out = FileOutputStream(file)
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush(); out.close()

            var freshnessString = ""
            if (eyesToSave.isNotEmpty()) {
                val totalEyes = eyesToSave.size
                val freshCount = eyesToSave.count { box ->
                    val label = box.clsName.lowercase()
                    !label.contains("non") && !label.contains("spoil")
                }
                val freshRatio = (freshCount.toFloat() / totalEyes) * 100
                // UPDATED: "Approx Freshness"
                freshnessString = "Approx Freshness: ${freshRatio.toInt()}% ($freshCount/$totalEyes);;;"
            }

            val fishCountList = resultsToSave.map { "${it.clsName} ${(it.cnf * 100).toInt()}%" }.toMutableList()
            if (eyesToSave.isNotEmpty()) fishCountList.add("Eyes: ${eyesToSave.size}")
            val countsString = fishCountList.joinToString(", ")

            val details = "$freshnessString Total: ${resultsToSave.size}, Eyes: ${eyesToSave.size}, Conf: ${resultsToSave.map { String.format("%.2f", it.cnf) }}"

            val metricsObj = org.json.JSONObject()
            val speciesObj = org.json.JSONObject()
            resultsToSave.groupBy { it.clsName }.forEach { (cls, boxes) ->
                speciesObj.put(cls, boxes.size)
            }
            metricsObj.put("species", speciesObj)
            metricsObj.put("totalCatches", resultsToSave.size)

            if (eyesToSave.isNotEmpty()) {
                val eyeObj = org.json.JSONObject()
                eyeObj.put("total", eyesToSave.size)
                val freshCount = eyesToSave.count { box ->
                    val label = box.clsName.lowercase()
                    !label.contains("non") && !label.contains("spoil")
                }
                eyeObj.put("fresh", freshCount)
                metricsObj.put("eyeCondition", eyeObj)
            }

            val db = DatabaseHelper(context)
            db.insertDetection(
                timestamp = System.currentTimeMillis(),
                imagePath = file.absolutePath,
                fishCount = countsString.ifEmpty { context.getString(R.string.none) },
                details = details,
                lat = lat,
                lng = lng,
                placeName = placeName,
                metricsJson = metricsObj.toString()
            )

            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Saved at $placeName", Toast.LENGTH_SHORT).show()
            }
            triggerBackgroundSync(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerBackgroundSync(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) binding.viewFinder.post { startCamera() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't close detectors — they're cached in DetectorCache for fast re-entry
        // Only shutdown the executor thread
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        lifecycleScope.launch(Dispatchers.IO) {
            segmentationMutex.withLock {
                coinSegmentation?.close()
                fishSegmentation?.close()
                coinSegmentation = null
                fishSegmentation = null
            }
        }
    }

    private fun updateTotalCount() {
        if (_binding == null) return
        val fishCount = lastResults.size
        val eyeCount = lastEyeResults.size
        val total = max(fishCount, eyeCount)
        binding.totalCountLabel.text = getString(R.string.total_detected, total)
    }

    override fun onEmptyDetect() {
        lastResults = emptyList()
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.overlay.setResults(emptyList(), emptyList())
                updateTotalCount()
                binding.inferenceTime.text = "Detecting: 0"
                binding.loadingProgress.visibility = View.GONE

                // Show no-detection card with fade-in animation
                binding.noDetectionText.alpha = 0f
                binding.noDetectionText.visibility = View.VISIBLE
                binding.noDetectionText.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start()

                binding.detectionList.visibility = View.GONE
                detectionAdapter.updateDetections(emptyList())
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        lastResults = boundingBoxes
        activity?.runOnUiThread {
            if (_binding != null) {
                // Combined HUD display: count + inference time
                binding.inferenceTime.text = "Detecting: ${boundingBoxes.size}  ·  ${inferenceTime}ms"
                binding.loadingProgress.visibility = View.GONE

                val groupedList = boundingBoxes.groupBy { it.clsName }

                val overlayColors = boundingBoxes.map { box ->
                    speciesColorMap.getOrPut(box.clsName) {
                        boxColors[speciesColorMap.size % boxColors.size]
                    }
                }

                binding.overlay.setResults(boundingBoxes, overlayColors)

                val detectionItems = groupedList.map { (species, boxes) ->
                    val count = boxes.size
                    val avgConf = boxes.map { it.cnf }.average().toFloat()
                    val color = speciesColorMap[species] ?: Color.WHITE

                    DetectionItem(
                        fishName = species,
                        count = count,
                        avgConfidence = avgConf,
                        color = color
                    )
                }

                updateTotalCount()
                if (detectionItems.isEmpty()) {
                    // Show error card with animation
                    binding.noDetectionText.alpha = 0f
                    binding.noDetectionText.visibility = View.VISIBLE
                    binding.noDetectionText.animate().alpha(1f).setDuration(400).start()
                    binding.detectionList.visibility = View.GONE
                } else {
                    binding.noDetectionText.visibility = View.GONE
                    // Fade in detection list
                    binding.detectionList.alpha = 0f
                    binding.detectionList.visibility = View.VISIBLE
                    binding.detectionList.animate().alpha(1f).setDuration(300).start()
                    detectionAdapter.updateDetections(detectionItems)
                }

                if (!isCameraRunning) {
                    calculateSpeciesDistribution(boundingBoxes)
                    checkForProtectedSpecies(boundingBoxes)
                    // Slide panel up & show save dialog once results are ready
                    slidePanelUp()
                    binding.saveDialog.visibility = View.VISIBLE
                    // Note: biomass is updated by runSegmentationPipeline which runs in parallel
                }
            }
        }
    }

    private fun slidePanelUp() {
        if (_binding == null) return
        
        binding.detectionPanelContainer.visibility = View.VISIBLE
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun slidePanelDown() {
        if (_binding == null) return
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    companion object {
        private const val TAG = "Camera"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()
    }

    // ========== PROTECTED SPECIES DETECTION ==========

    private val alertedSpecies = mutableSetOf<String>()

    private fun checkForProtectedSpecies(boxes: List<BoundingBox>) {
        for (box in boxes) {
            val speciesName = box.clsName
            if (alertedSpecies.contains(speciesName)) continue

            val info = ProtectedSpeciesRepository.getProtectionInfo(speciesName)
            if (info != null) {
                alertedSpecies.add(speciesName)
                showProtectedSpeciesAlert(info.commonName, info.scientificName, info.protectionStatus, info.iucnStatus, info.conservationNote)
            }
        }
    }

    private fun showProtectedSpeciesAlert(commonName: String, scientificName: String, protection: String, iucn: String, note: String) {
        if (_binding == null || context == null) return

        val iucnLabel = when (iucn) {
            "CR" -> "Critically Endangered"
            "EN" -> "Endangered"
            "VU" -> "Vulnerable"
            "NT" -> "Near Threatened"
            else -> iucn
        }

        val message = buildString {
            appendLine("$commonName ($scientificName)")
            appendLine()
            appendLine("Protection: $protection")
            appendLine("IUCN Status: $iucnLabel")
            appendLine()
            appendLine(note)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.protected_species_alert))
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.log_conservation)) { _, _ ->
                logProtectedSpeciesDetection(commonName)
            }
            .setNegativeButton(getString(R.string.dismiss), null)
            .setCancelable(true)
            .show()
    }

    private fun logProtectedSpeciesDetection(speciesName: String) {
        val appContext = context?.applicationContext ?: return
        val bitmap = lastBitmap ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filename = "protected_${System.currentTimeMillis()}.jpg"
                val file = File(appContext.filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush(); out.close()

                val db = DatabaseHelper(appContext)
                db.insertLog(
                    timestamp = System.currentTimeMillis(),
                    imagePath = file.absolutePath,
                    title = "Protected: $speciesName",
                    details = ProtectedSpeciesRepository.getConservationAlert(speciesName),
                    type = DatabaseHelper.TYPE_PROTECTED,
                    lat = capturedLocation?.latitude ?: 0.0,
                    lng = capturedLocation?.longitude ?: 0.0,
                    placeName = "Conservation Log"
                )

                launch(Dispatchers.Main) {
                    Toast.makeText(appContext, "Protected species logged for conservation", Toast.LENGTH_SHORT).show()
                }

                // Trigger sync
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).build()
                WorkManager.getInstance(appContext).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log protected species", e)
            }
        }
    }
}