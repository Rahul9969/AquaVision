package com.rahul.aquavision.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.rahul.aquavision.R
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.SyncWorker
import com.rahul.aquavision.ml.BoundingBox
import com.rahul.aquavision.ml.Detector
import com.rahul.aquavision.ml.OnnxFreshnessClassifier
import com.rahul.aquavision.databinding.FragmentFreshnessBinding
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.rahul.aquavision.ml.segmentation.utils.Utils
import com.rahul.aquavision.ui.camera.InAppCameraActivity
import android.content.Intent
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!

    private var fishDetector: Detector? = null
    private var featureDetector: Detector? = null
    private var freshnessClassifier: OnnxFreshnessClassifier? = null

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var cameraExecutor: ExecutorService

    private var tempImageUri: Uri? = null

    private var lastBitmapEyes: Bitmap? = null
    private var lastEyesBoxes: List<BoundingBox> = emptyList()
    
    // Store raw model confidence (0-1) and whether it predicted fresh
    private var eyesRawConf: Float? = null
    private var eyesIsFresh: Boolean? = null
    private var finalVerdictStr: String = ""

    // Captured location store
    private var capturedLocation: Location? = null

    /**
     * Tracks the pixel-space crop region so we can map feature boxes
     * from crop-normalized coordinates back to original image coordinates.
     */
    private data class CropRegion(
        val pixelX1: Int, val pixelY1: Int,
        val pixelX2: Int, val pixelY2: Int,
        val srcWidth: Int, val srcHeight: Int
    )

    private val dummyListener = object : Detector.DetectorListener {
        override fun onEmptyDetect() {}
        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {}
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uriString = result.data?.getStringExtra(InAppCameraActivity.EXTRA_PHOTO_URI)
            uriString?.let { startCrop(Uri.parse(it)) }
        }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { processFinalImage(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(context, getString(R.string.crop_error), Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) launchCamera()
        else Toast.makeText(context, getString(R.string.camera_permission_needed), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFreshnessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        OpenCVLoader.initDebug()
        
        dbHelper = DatabaseHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        initDetectors()
        setupButtons()
        loadGifs()
    }

    private fun initDetectors() {
        cameraExecutor.execute {
            context?.let { safeContext ->
                fishDetector = Detector(safeContext, "model_yolov11m_float16.tflite", "labels.txt", dummyListener, 0.25f)
                featureDetector = Detector(safeContext, "freshness_feature_float16.tflite", "features_labels.txt", dummyListener, 0.05f)
                freshnessClassifier = OnnxFreshnessClassifier(safeContext)
            }
        }
    }

    private fun loadGifs() {
        if (lastBitmapEyes == null) {
            Glide.with(this)
                .asGif()
                .load(R.drawable.eyes_instruction)
                .into(binding.gifInstructionsEyes)
        }
    }

    private fun setupButtons() {
        binding.btnCameraEyes.setOnClickListener { checkPermissionAndLaunchCamera() }
        binding.btnGalleryEyes.setOnClickListener {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnSaveResult.setOnClickListener { binding.saveDialog.visibility = View.VISIBLE }
        binding.btnDialogDiscard.setOnClickListener { binding.saveDialog.visibility = View.GONE }
        binding.btnDialogSave.setOnClickListener { saveFreshnessLog(); binding.saveDialog.visibility = View.GONE }
    }

    private fun checkPermissionAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun launchCamera() {
        try {
            val intent = Intent(requireContext(), InAppCameraActivity::class.java)
            takePictureLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_starting_camera), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCrop(sourceUri: Uri) {
        val destFileName = "crop_${System.currentTimeMillis()}.jpg"
        val destFile = File(requireContext().cacheDir, destFileName)
        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setToolbarTitle(getString(R.string.crop_eyes))
            setFreeStyleCropEnabled(true)
        }
        cropImage.launch(UCrop.of(sourceUri, Uri.fromFile(destFile)).withOptions(options).getIntent(requireContext()))
    }

    private fun processFinalImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap = Utils.rotateImageIfRequired(requireContext(), bitmap, uri)
            bitmap = Utils.resizeBitmap(bitmap, 640)

            binding.imgEyes.setImageBitmap(bitmap)
            binding.imgEyes.visibility = View.VISIBLE
            binding.gifInstructionsEyes.visibility = View.GONE

            binding.overlayEyes.clear()
            binding.overlayEyes.setImageDimensions(bitmap.width, bitmap.height)
            
            // Reset state
            eyesRawConf = null
            eyesIsFresh = null
            binding.txtResultEyes.text = getString(R.string.no_detection)
            binding.txtResultEyes.background.setTint(Color.parseColor("#F5F5F5"))
            binding.txtResultEyes.setTextColor(Color.parseColor("#757575"))
            binding.cardFinalVerdict.visibility = View.GONE
            binding.cardPreviews.visibility = View.GONE
            binding.layoutProgressBars.visibility = View.GONE
            binding.btnSaveResult.visibility = View.GONE

            binding.pbEyesLoading.visibility = View.VISIBLE

            capturedLocation = null
            prefetchLocation()

            // Run advanced inference pipeline
            lifecycleScope.launch(Dispatchers.Default) {
                runFreshnessPipeline(bitmap)
            }

        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_loading_gallery_image), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Full freshness analysis pipeline:
     *
     * 1. YOLOv11 detects the fish → best fish bounding box
     * 2. Crop the fish from the original image
     * 3. Feature detection (eyes/gills) runs on the RAW fish crop (not preprocessed)
     * 4. Map eye/gill boxes from crop-space → original image space for display
     * 5. Preprocess the fish crop (CLAHE + sharpen)
     * 6. Crop eye/gill from the preprocessed image → re-preprocess each
     * 7. Three images (eye, gills, whole fish) go to the freshness classifier
     * 8. Weighted average produces final freshness score
     * 9. Display: original image + fish box (dashed) + eye/gill boxes (colored)
     *           + 3 crop preview thumbnails with per-region scores
     */
    private suspend fun runFreshnessPipeline(originalBitmap: Bitmap) {
        try {
            // ── Step 1: Detect fish with YOLOv11 ─────────────────────────────
            val fishBoxes = fishDetector?.detectSync(originalBitmap)
            val bestFish = fishBoxes?.maxByOrNull { it.cnf }

            // ── Step 2: Crop the fish (with region tracking) ─────────────────
            var fishCropRegion: CropRegion? = null
            val fishBoxForDisplay: BoundingBox? = bestFish

            val fishCrop = if (fishBoxForDisplay != null) {
                val (crop, region) = cropBitmapWithRegion(originalBitmap, fishBoxForDisplay)
                fishCropRegion = region
                crop
            } else {
                fishCropRegion = CropRegion(0, 0, originalBitmap.width, originalBitmap.height, originalBitmap.width, originalBitmap.height)
                null
            }

            // Use the fish crop for further processing, or the full image if no fish detected
            val rawCrop = fishCrop ?: originalBitmap

            // ── Step 2.5: Preprocess BEFORE feature detection ────────────
            // CRITICAL: The Python reference preprocesses (CLAHE + sharpen)
            // BEFORE running feature detection (test_freshness_app.py line 399).
            // CLAHE normalizes lighting and enhances contrast around eye/gill
            // areas, making them much easier for the feature model to detect.
            val processedCrop = freshnessClassifier?.preprocessCrop(rawCrop) ?: rawCrop

            // ── Step 3: Feature detection ────────────────────────────────────
            // Run on the PREPROCESSED image (matching Python exactly).
            val (bestEye, bestGill) = featureDetector?.detectFeaturesPerClassSync(processedCrop) ?: Pair(null, null)

            // Debug logging
            android.util.Log.d("FreshnessPipeline",
                "Detection: eye=cnf=${"%.4f".format(bestEye?.cnf ?: 0f)} " +
                "box=[x1=${"%.2f".format(bestEye?.x1 ?: 0f)},y1=${"%.2f".format(bestEye?.y1 ?: 0f)}," +
                "x2=${"%.2f".format(bestEye?.x2 ?: 0f)},y2=${"%.2f".format(bestEye?.y2 ?: 0f)}] | " +
                "gill=cnf=${"%.4f".format(bestGill?.cnf ?: 0f)} " +
                "box=[x1=${"%.2f".format(bestGill?.x1 ?: 0f)},y1=${"%.2f".format(bestGill?.y1 ?: 0f)}," +
                "x2=${"%.2f".format(bestGill?.x2 ?: 0f)},y2=${"%.2f".format(bestGill?.y2 ?: 0f)}]"
            )

            // ── Step 4: Map boxes from crop-space → original image space ─────
            // Apply a minimum confidence threshold to prevent false-positives
            // when the image is just a macro photo of an eye.
            val MIN_CONF_THRESH = 0.05f
            val validEye = bestEye?.takeIf { it.cnf >= MIN_CONF_THRESH }
            val validGill = bestGill?.takeIf { it.cnf >= MIN_CONF_THRESH }

            // Map to original image space for overlay display
            val displayEyeBox = validEye?.let { mapBoxToOriginal(it, fishCropRegion!!) }
            val displayGillBox = validGill?.let { mapBoxToOriginal(it, fishCropRegion!!) }

            val displayBoxes = listOfNotNull(displayEyeBox, displayGillBox)
            lastEyesBoxes = displayBoxes
            lastBitmapEyes = originalBitmap

            // ── Step 6: Crop eye/gill from preprocessed, re-preprocess ───────
            val eyeBoxForCrop = validEye
            val gillBoxForCrop = validGill

            // Python crops from the preprocessed image (`img_np = preprocess_crop(img_np)` in Step 2)
            // UI preview thumbnails (matches Python: from preprocessed image)
            val eyeRawCrop = if (eyeBoxForCrop != null) cropBitmap(processedCrop, eyeBoxForCrop) else null
            val gillRawCrop = if (gillBoxForCrop != null) cropBitmap(processedCrop, gillBoxForCrop) else null

            // Preprocessed crops for the classifier (matches Python: from preprocessed image)
            var eyeCropForModel = if (eyeBoxForCrop != null) cropBitmap(processedCrop, eyeBoxForCrop) else null
            var gillCropForModel = if (gillBoxForCrop != null) cropBitmap(processedCrop, gillBoxForCrop) else null

            // Re-preprocess each crop (CLAHE + sharpen) — matches Python _safe_crop
            eyeCropForModel = eyeCropForModel?.let { freshnessClassifier?.preprocessCrop(it) }
            gillCropForModel = gillCropForModel?.let { freshnessClassifier?.preprocessCrop(it) }

            // ── Step 7: Classify all three images ────────────────────────────
            val eyeProbs = eyeCropForModel?.let { freshnessClassifier?.analyzeFreshnessTTA(it) }
            val gillProbs = gillCropForModel?.let { freshnessClassifier?.analyzeFreshnessTTA(it) }
            val wholeProbs = freshnessClassifier?.analyzeFreshnessTTA(processedCrop)

            // ── Step 8: Compute Weighted Average (Exact Python Logic) ────────
            val finalProbs = FloatArray(3) { 0f }
            var hasPrediction = false

            if (eyeProbs != null && gillProbs != null && wholeProbs != null) {
                for (i in 0..2) finalProbs[i] = 0.4f * eyeProbs[i] + 0.4f * gillProbs[i] + 0.2f * wholeProbs[i]
                hasPrediction = true
            } else if (eyeProbs != null && wholeProbs != null) {
                for (i in 0..2) finalProbs[i] = 0.7f * eyeProbs[i] + 0.3f * wholeProbs[i]
                hasPrediction = true
            } else if (gillProbs != null && wholeProbs != null) {
                for (i in 0..2) finalProbs[i] = 0.7f * gillProbs[i] + 0.3f * wholeProbs[i]
                hasPrediction = true
            } else if (wholeProbs != null) {
                for (i in 0..2) finalProbs[i] = wholeProbs[i]
                hasPrediction = true
            }

            if (hasPrediction) {
                // ── Step 9: Update UI ────────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateUIAfterPipeline(
                        originalBitmap = originalBitmap,
                        fishBox = fishBoxForDisplay,
                        featureBoxes = displayBoxes,
                        finalProbs = finalProbs,
                        eyePreview = eyeRawCrop,
                        gillPreview = gillRawCrop,
                        wholePreview = processedCrop,
                        eyeProbs = eyeProbs,
                        gillProbs = gillProbs,
                        wholeProbs = wholeProbs
                    )
                }
            } else {

                withContext(Dispatchers.Main) {
                    binding.pbEyesLoading.visibility = View.GONE
                    Toast.makeText(context, "Analysis Failed. Could not compute freshness.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                binding.pbEyesLoading.visibility = View.GONE
            }
        }
    }

    /**
     * Crop a bitmap using a bounding box AND return the pixel-space crop region
     * so that feature detection boxes can later be mapped back to the original.
     */

    private fun cropBitmapWithRegion(bitmap: Bitmap, box: BoundingBox): Pair<Bitmap?, CropRegion?> {
        val imgW = bitmap.width
        val imgH = bitmap.height
        var x1 = (box.x1 * imgW).toInt()
        var y1 = (box.y1 * imgH).toInt()
        var x2 = (box.x2 * imgW).toInt()
        var y2 = (box.y2 * imgH).toInt()

        val padX = ((x2 - x1) * 0.05).toInt()
        val padY = ((y2 - y1) * 0.05).toInt()
        x1 = maxOf(0, x1 - padX)
        y1 = maxOf(0, y1 - padY)
        x2 = minOf(imgW, x2 + padX)
        y2 = minOf(imgH, y2 + padY)

        if (x1 >= x2 || y1 >= y2 || x1 < 0 || y1 < 0) return Pair(null, null)

        val region = CropRegion(x1, y1, x2, y2, imgW, imgH)
        val bmp = try {
            Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        } catch (e: Exception) { null }

        return Pair(bmp, region)
    }

    /**
     * Map a bounding box from crop-normalized coordinates to original
     * image-normalized coordinates using the stored crop region.
     *
     * Feature box coords are 0-1 relative to the crop.
     * We need to transform them to be 0-1 relative to the original image.
     */
    private fun mapBoxToOriginal(box: BoundingBox, region: CropRegion): BoundingBox {
        val cropW = (region.pixelX2 - region.pixelX1).toFloat()
        val cropH = (region.pixelY2 - region.pixelY1).toFloat()
        val srcW = region.srcWidth.toFloat()
        val srcH = region.srcHeight.toFloat()

        val newX1 = ((region.pixelX1 + box.x1 * cropW) / srcW).coerceIn(0f, 1f)
        val newY1 = ((region.pixelY1 + box.y1 * cropH) / srcH).coerceIn(0f, 1f)
        val newX2 = ((region.pixelX1 + box.x2 * cropW) / srcW).coerceIn(0f, 1f)
        val newY2 = ((region.pixelY1 + box.y2 * cropH) / srcH).coerceIn(0f, 1f)

        return box.copy(
            x1 = newX1, y1 = newY1, x2 = newX2, y2 = newY2,
            cx = (newX1 + newX2) / 2f,
            cy = (newY1 + newY2) / 2f,
            w = newX2 - newX1,
            h = newY2 - newY1
        )
    }

    private fun cropBitmap(bitmap: Bitmap, box: BoundingBox): Bitmap? {
        val imgW = bitmap.width
        val imgH = bitmap.height
        var x1 = (box.x1 * imgW).toInt()
        var y1 = (box.y1 * imgH).toInt()
        var x2 = (box.x2 * imgW).toInt()
        var y2 = (box.y2 * imgH).toInt()
        
        val padX = ((x2 - x1) * 0.05).toInt()
        val padY = ((y2 - y1) * 0.05).toInt()
        x1 = maxOf(0, x1 - padX)
        y1 = maxOf(0, y1 - padY)
        x2 = minOf(imgW, x2 + padX)
        y2 = minOf(imgH, y2 + padY)
        
        if (x1 >= x2 || y1 >= y2 || x1 < 0 || y1 < 0) return null
        return try {
            Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Update all UI elements after pipeline analysis completes.
     *
     * @param originalBitmap  The full original image (displayed in imgEyes)
     * @param fishBox         The fish detection box (drawn as dashed white outline)
     * @param featureBoxes    Eye/gill boxes mapped to original image coordinates
     * @param finalProbs      Weighted freshness probabilities [Fresh, Medium, Stale]
     * @param eyePreview      Raw eye crop bitmap for preview thumbnail
     * @param gillPreview     Raw gill crop bitmap for preview thumbnail
     * @param wholePreview    Raw fish crop bitmap for preview thumbnail
     * @param eyeProbs        Individual eye freshness probs (nullable if not detected)
     * @param gillProbs       Individual gill freshness probs (nullable if not detected)
     * @param wholeProbs      Individual whole-fish freshness probs (nullable)
     */
    private fun updateUIAfterPipeline(
        originalBitmap: Bitmap,
        fishBox: BoundingBox?,
        featureBoxes: List<BoundingBox>,
        finalProbs: FloatArray,
        eyePreview: Bitmap?,
        gillPreview: Bitmap?,
        wholePreview: Bitmap?,
        eyeProbs: FloatArray?,
        gillProbs: FloatArray?,
        wholeProbs: FloatArray?
    ) {
        binding.pbEyesLoading.visibility = View.GONE

        // ── Show original image with all bounding boxes ──────────────────
        binding.imgEyes.setImageBitmap(originalBitmap)
        binding.imgEyes.post {
            binding.overlayEyes.setImageDimensions(originalBitmap.width, originalBitmap.height)
            binding.overlayEyes.setFishBox(fishBox)
            binding.overlayEyes.setEyeResults(featureBoxes)
        }

        // ── Detection status text ────────────────────────────────────────
        val statusText = when {
            featureBoxes.size >= 2 -> "Eye & Gills Detected"
            featureBoxes.size == 1 -> if (featureBoxes[0].clsName.lowercase().contains("eye")) "Eye Detected" else "Gills Detected"
            else -> "Whole Fish Baseline Used"
        }
        binding.txtResultEyes.text = statusText
        binding.txtResultEyes.setTextColor(Color.parseColor("#1B5E20"))
        binding.txtResultEyes.background.setTint(Color.parseColor("#E8F5E9"))

        // ── Populate preview thumbnails ──────────────────────────────────
        binding.cardPreviews.visibility = View.VISIBLE

        if (eyePreview != null) {
            binding.imgPreviewEye.setImageBitmap(eyePreview)
        } else {
            binding.imgPreviewEye.setImageResource(0) // Clear
        }
        if (gillPreview != null) {
            binding.imgPreviewGill.setImageBitmap(gillPreview)
        } else {
            binding.imgPreviewGill.setImageResource(0)
        }
        if (wholePreview != null) {
            binding.imgPreviewWhole.setImageBitmap(wholePreview)
        } else {
            binding.imgPreviewWhole.setImageResource(0)
        }

        // Per-region freshness scores
        val classes = arrayOf("Fresh", "Medium", "Stale")
        fun formatScore(probs: FloatArray?): String {
            if (probs == null) return "Not detected"
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            return "${classes[maxIdx]} ${(probs[maxIdx] * 100).toInt()}%"
        }
        fun scoreColor(probs: FloatArray?): Int {
            if (probs == null) return Color.parseColor("#757575")
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            return when (maxIdx) {
                0 -> Color.parseColor("#2E7D32")  // Fresh → green
                1 -> Color.parseColor("#F57F17")  // Medium → amber
                else -> Color.parseColor("#C62828") // Stale → red
            }
        }

        binding.tvEyeScore.text = formatScore(eyeProbs)
        binding.tvEyeScore.setTextColor(scoreColor(eyeProbs))
        binding.tvGillScore.text = formatScore(gillProbs)
        binding.tvGillScore.setTextColor(scoreColor(gillProbs))
        binding.tvWholeScore.text = formatScore(wholeProbs)
        binding.tvWholeScore.setTextColor(scoreColor(wholeProbs))

        // ── Final Verdict ────────────────────────────────────────────────
        binding.cardFinalVerdict.visibility = View.VISIBLE
        binding.btnSaveResult.visibility = View.VISIBLE
        binding.layoutProgressBars.visibility = View.VISIBLE

        val maxProbIdx = finalProbs.indices.maxByOrNull { finalProbs[it] } ?: 0
        val predClass = classes[maxProbIdx]
        val confPercent = (finalProbs[maxProbIdx] * 100).toInt()

        eyesRawConf = finalProbs[0] // Save strictly "Fresh" proportion for history records
        eyesIsFresh = (maxProbIdx == 0)
        finalVerdictStr = "$predClass ($confPercent%)"

        binding.txtFinalResult.text = finalVerdictStr
        when (maxProbIdx) {
            0 -> binding.txtFinalResult.setTextColor(Color.parseColor("#2E7D32"))
            1 -> binding.txtFinalResult.setTextColor(Color.parseColor("#F57F17"))
            2 -> binding.txtFinalResult.setTextColor(Color.parseColor("#C62828"))
        }

        binding.pbFresh.progress = (finalProbs[0] * 100).toInt()
        binding.pbMedium.progress = (finalProbs[1] * 100).toInt()
        binding.pbStale.progress = (finalProbs[2] * 100).toInt()

        binding.tvFreshLabel.text = "Fresh: ${(finalProbs[0] * 100).toInt()}%"
        binding.tvMediumLabel.text = "Medium: ${(finalProbs[1] * 100).toInt()}%"
        binding.tvStaleLabel.text = "Stale: ${(finalProbs[2] * 100).toInt()}%"
    }

    // --- Pre-fetch Location ---
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

    private fun saveFreshnessLog() {
        val appContext = requireContext().applicationContext
        val paths = mutableListOf<String>()

        lastBitmapEyes?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(appContext, bmp, lastEyesBoxes)
            val filename = "fresh_${System.currentTimeMillis()}.jpg"
            val file = File(appContext.filesDir, filename)
            try {
                val out = FileOutputStream(file)
                drawnBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                paths.add(file.absolutePath)
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (paths.isEmpty()) return

        val combinedPaths = paths.joinToString("|")
        val finalPercent = if (eyesRawConf != null) (eyesRawConf!! * 100).toInt() else 0
        val combinedDetails = "Multi-Stage Analysis;;;Eye Condition: ${finalPercent}%"
        val title = binding.txtFinalResult.text.toString()

        if (capturedLocation != null) {
            performInsert(appContext, combinedPaths, title, combinedDetails, capturedLocation!!.latitude, capturedLocation!!.longitude, "Lat: ${capturedLocation!!.latitude}, Lng: ${capturedLocation!!.longitude}")
            return
        }

        Toast.makeText(appContext, "Acquiring GPS...", Toast.LENGTH_SHORT).show()

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        @Suppress("DEPRECATION")
                        var placeName = appContext.getString(R.string.location_not_available)
                        try {
                            val geocoder = Geocoder(appContext, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            if (!addresses.isNullOrEmpty()) {
                                placeName = addresses[0].locality ?: addresses[0].getAddressLine(0)
                            } else {
                                placeName = "Lat: ${location.latitude}, Lng: ${location.longitude}"
                            }
                        } catch (e: Exception) {
                            placeName = "Lat: ${location.latitude}, Lng: ${location.longitude}"
                        }
                        performInsert(appContext, combinedPaths, title, combinedDetails, location.latitude, location.longitude, placeName)
                    } else {
                        performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, appContext.getString(R.string.location_not_available))
                    }
                }
                .addOnFailureListener {
                    performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, appContext.getString(R.string.location_not_available))
                }
        } else {
            performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, getString(R.string.location_not_available))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun performInsert(context: Context, paths: String, title: String, details: String, lat: Double, lng: Double, placeName: String) {
        try {
            val db = DatabaseHelper(context)
            db.insertLog(System.currentTimeMillis(), paths, title, details, lat, lng, placeName, DatabaseHelper.TYPE_FRESHNESS)
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND_OR_REPLACE, syncRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun drawBoundingBoxes(context: Context, bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
        
        boxes.forEach { box ->
            // Use Green for Eye (cls=0) and Yellow/Cyan for Gills (cls=1)
            val boxColor = if (box.cls == 0) Color.GREEN else Color.parseColor("#FFFF00")
            val boxPaint = Paint().apply { color = boxColor; style = Paint.Style.STROKE; strokeWidth = 8f }
            
            val left = box.x1 * mutableBitmap.width
            val top = box.y1 * mutableBitmap.height
            val right = box.x2 * mutableBitmap.width
            val bottom = box.y2 * mutableBitmap.height
            
            canvas.drawRect(left, top, right, bottom, boxPaint)
            
            // Background for text
            val textPaintBg = Paint().apply { color = boxColor; style = Paint.Style.FILL }
            val label = box.clsName
            canvas.drawRect(left, top - 45f, left + textPaint.measureText(label) + 10f, top, textPaintBg)
            canvas.drawText(label, left + 5f, top - 10f, textPaint)
        }
        return mutableBitmap
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute {
                fishDetector?.close()
                featureDetector?.close()
                freshnessClassifier?.close()
            }
            cameraExecutor.shutdown()
        }
        _binding = null
    }
}