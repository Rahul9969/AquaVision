package com.rahul.aquavision.ui.volume

import com.rahul.aquavision.ui.camera.InAppCameraActivity
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.rahul.aquavision.R
import com.rahul.aquavision.data.Constants
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.SyncWorker
import com.rahul.aquavision.databinding.DialogSettingsBinding
import com.rahul.aquavision.databinding.FragmentVolumeBinding
import com.rahul.aquavision.ml.BoundingBox
import com.rahul.aquavision.ml.Detector
import com.rahul.aquavision.ml.segmentation.AnalysisResult
import com.rahul.aquavision.ml.segmentation.DrawImages
import com.rahul.aquavision.ml.segmentation.InstanceSegmentation
import com.rahul.aquavision.ml.segmentation.SegmentationResult
import com.rahul.aquavision.ml.segmentation.Success
import com.rahul.aquavision.ml.segmentation.ui.SettingsViewModel
import com.rahul.aquavision.ml.segmentation.ui.ViewPagerAdapter
import com.rahul.aquavision.ml.segmentation.utils.Utils
import com.rahul.aquavision.ml.segmentation.utils.Utils.addCarouselEffect
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import org.opencv.aruco.Aruco
import org.opencv.aruco.DetectorParameters
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import android.content.Intent
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class VolumeFragment : Fragment(), Detector.DetectorListener {

    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by activityViewModels()

    private var instanceSegmentation: InstanceSegmentation? = null
    private var coinSegmentation: InstanceSegmentation? = null
    private var detector: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var drawImages: DrawImages
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private var currentBitmap: Bitmap? = null
    private var currentScale: Float = 50.0f
    private var isMarkerDetected: Boolean = false

    private val segmentationMutex = Mutex()

    private var lastAnalysisResult: List<AnalysisResult>? = null
    private var currentPhotoUri: Uri? = null

    private var originalBitmap: Bitmap? = null

    // Captured location store
    private var capturedLocation: Location? = null

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let { uri ->
                var bitmap = Utils.getBitmapFromUri(requireContext(), uri) ?: return@let

                bitmap = Utils.resizeBitmap(bitmap, 1024)

                binding.instructionGifView.visibility = View.GONE

                originalBitmap = bitmap
                currentBitmap = bitmap

                currentScale = 50.0f
                isMarkerDetected = false

                if (viewModel.useCoinReference) {
                    detector?.detect(bitmap)
                } else {
                    val (markedBitmap, scale, found) = detectArUcoMarkers(bitmap)
                    currentBitmap = markedBitmap
                    currentScale = scale
                    isMarkerDetected = found
                    detector?.detect(markedBitmap)
                }

                // Start Pre-fetch
                capturedLocation = null
                prefetchLocation()
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val error = UCrop.getError(result.data!!)
            toast(getString(R.string.crop_error_with_message, error?.message))
        }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    private val photoCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uriString = result.data?.getStringExtra(InAppCameraActivity.EXTRA_PHOTO_URI)
            uriString?.let { uri -> startCrop(Uri.parse(uri)) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVolumeBinding.inflate(inflater, container, false)
        viewPagerAdapter = ViewPagerAdapter(mutableListOf())
        binding.viewpager.adapter = viewPagerAdapter
        binding.viewpager.addCarouselEffect()

        binding.viewpager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateArrowVisibility(position, viewPagerAdapter.itemCount)
            }
        })

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        OpenCVLoader.initDebug()

        instanceSegmentation = InstanceSegmentation(
            requireContext(),
            Constants.SEG_MODEL_PATH,
            null,
            "Fish",
            5
        ) { toast(getString(R.string.fish_error, it)) }

        coinSegmentation = InstanceSegmentation(
            requireContext(),
            Constants.COIN_MODEL_PATH,
            null,
            "Coin",
            5
        ) { toast(getString(R.string.coin_error, it)) }

        detector = Detector(requireContext(), Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        drawImages = DrawImages(requireContext())

        setupListeners()
        loadInstructionGif()
    }

    private fun loadInstructionGif() {
        if (currentBitmap == null) {
            binding.instructionGifView.visibility = View.VISIBLE
            Glide.with(this)
                .asGif()
                .load(R.drawable.instruction_video)
                .into(binding.instructionGifView)
        } else {
            binding.instructionGifView.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnCamera.setOnClickListener {
            val intent = Intent(requireContext(), InAppCameraActivity::class.java)
            photoCapture.launch(intent)
        }
        binding.btnGallery.setOnClickListener {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnSave.setOnClickListener {
            if (currentBitmap != null) binding.saveDialog.visibility = View.VISIBLE
            else toast(getString(R.string.no_analysis_to_save))
        }
        binding.btnDialogDiscard.setOnClickListener { binding.saveDialog.visibility = View.GONE }
        binding.btnDialogSave.setOnClickListener {
            saveVolumeLog()
            binding.saveDialog.visibility = View.GONE
        }
        binding.ivSettings.setOnClickListener { showSettingsDialog() }

        binding.btnPrev.setOnClickListener {
            val current = binding.viewpager.currentItem
            if (current > 0) binding.viewpager.currentItem = current - 1
        }
        binding.btnNext.setOnClickListener {
            val current = binding.viewpager.currentItem
            if (current < viewPagerAdapter.itemCount - 1) binding.viewpager.currentItem = current + 1
        }
    }

    private fun updateArrowVisibility(position: Int, count: Int) {
        if (count <= 1) {
            binding.btnPrev.visibility = View.GONE
            binding.btnNext.visibility = View.GONE
        } else {
            binding.btnPrev.visibility = if (position > 0) View.VISIBLE else View.GONE
            binding.btnNext.visibility = if (position < count - 1) View.VISIBLE else View.GONE
        }
    }

    private fun detectArUcoMarkers(bitmap: Bitmap): Triple<Bitmap, Float, Boolean> {
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)

        val rgbMat = Mat()
        Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val dicts = listOf(
            Aruco.DICT_4X4_50,
            Aruco.DICT_5X5_50,
            Aruco.DICT_6X6_50,
            Aruco.DICT_APRILTAG_36h11
        )

        val corners = ArrayList<Mat>()
        val ids = Mat()
        val parameters = DetectorParameters.create()

        var markerFound = false
        var detectedScale = 50.0f // Default fallback

        for (dictId in dicts) {
            val dictionary = Aruco.getPredefinedDictionary(dictId)
            corners.clear()
            ids.release()

            try {
                Aruco.detectMarkers(grayMat, dictionary, corners, ids, parameters)

                if (ids.rows() > 0) {
                    Scalar(0.0, 255.0, 0.0).let { green ->
                        Aruco.drawDetectedMarkers(rgbMat, corners, ids, green)
                    }

                    val markerRealSizeCm = 4.5f

                    val c = corners[0]
                    val xDiff = c.get(0, 0)[0] - c.get(0, 1)[0]
                    val yDiff = c.get(0, 0)[1] - c.get(0, 1)[1]

                    val widthPx = sqrt(xDiff.pow(2) + yDiff.pow(2)).toFloat()

                    detectedScale = widthPx / markerRealSizeCm
                    markerFound = true
                    break
                }
            } catch (e: Exception) {
                Log.e("VolumeFragment", "Error detection loop: ${e.message}")
            }
        }

        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(rgbMat, resultBitmap)

        mat.release(); rgbMat.release(); grayMat.release(); ids.release()
        corners.forEach { it.release() }

        return Triple(resultBitmap, detectedScale, markerFound)
    }

    private fun startCrop(sourceUri: Uri) {
        val destFile = File(requireContext().cacheDir, "crop_${System.currentTimeMillis()}.jpg")
        val options = UCrop.Options().apply {
            setToolbarTitle(getString(R.string.crop_fish))
            setFreeStyleCropEnabled(true)
            setCompressionQuality(90)
        }
        cropImage.launch(UCrop.of(sourceUri, Uri.fromFile(destFile)).withOptions(options).getIntent(requireContext()))
    }

    private fun runInstanceSegmentation(bitmap: Bitmap, speciesBoxes: List<BoundingBox>, defaultScale: Float) {
        requireActivity().runOnUiThread {
            binding.pbLoading.visibility = View.VISIBLE
            binding.tvNoFish.visibility = View.GONE
            binding.instructionGifView.visibility = View.GONE
            binding.btnSave.visibility = View.GONE
            binding.chipInference.visibility = View.GONE
            binding.btnPrev.visibility = View.GONE
            binding.btnNext.visibility = View.GONE
            viewPagerAdapter.updateImages(emptyList())
        }

        lifecycleScope.launch(Dispatchers.Default) {
            segmentationMutex.withLock {
                var coinResults: List<SegmentationResult> = emptyList()
                var activeScale = defaultScale
                var fishSuccess: Success? = null

                if (viewModel.useCoinReference && coinSegmentation != null) {
                    try {
                        coinSegmentation?.invoke(
                            frame = bitmap,
                            smoothEdges = false,
                            onSuccess = { success ->
                                coinResults = success.results
                                if (coinResults.isNotEmpty()) {
                                    val coinBox = coinResults.first().box
                                    val widthPx = coinBox.w * bitmap.width
                                    val heightPx = coinBox.h * bitmap.height
                                    val diameterPx = max(widthPx, heightPx)
                                    activeScale = diameterPx / 2.7f
                                    isMarkerDetected = true
                                }
                            },
                            onFailure = { Log.e("VolFrag", getString(R.string.coin_model_failed, it)) }
                        )
                    } catch (e: Exception) { Log.e("VolFrag", getString(R.string.coin_model_crashed), e) }
                }

                if (instanceSegmentation != null) {
                    try {
                        instanceSegmentation?.invoke(
                            frame = bitmap,
                            smoothEdges = viewModel.isSmoothEdges,
                            onSuccess = { success -> fishSuccess = success },
                            onFailure = { Log.e("VolFrag", getString(R.string.fish_model_failed, it)) }
                        )
                    } catch (e: Exception) { Log.e("VolFrag", getString(R.string.fish_model_crashed), e) }
                }

                val finalFishSuccess = fishSuccess ?: Success(0, 0, 0, emptyList())
                finalizeAndDraw(bitmap, finalFishSuccess, coinResults, speciesBoxes, activeScale)
            }
        }
    }

    private fun finalizeAndDraw(
        original: Bitmap,
        fishSuccess: Success,
        coinResults: List<SegmentationResult>,
        speciesBoxes: List<BoundingBox>,
        scale: Float
    ) {
        requireActivity().runOnUiThread {
            binding.pbLoading.visibility = View.GONE
            binding.chipInference.text = getString(R.string.inference_time_ms, fishSuccess.interfaceTime)
            binding.chipInference.visibility = View.VISIBLE

            if (fishSuccess.results.isEmpty() && coinResults.isEmpty()) {
                binding.tvNoFish.visibility = View.VISIBLE
                binding.tvNoFish.text = getString(R.string.no_fish_or_coin_detected)
                binding.btnSave.visibility = View.GONE
                viewPagerAdapter.updateImages(emptyList())
                lastAnalysisResult = null
                updateArrowVisibility(0, 0)
            } else {
                binding.tvNoFish.visibility = View.GONE
                binding.btnSave.visibility = View.VISIBLE

                val analysisResults = drawImages.invoke(
                    original = original,
                    success = fishSuccess,
                    coinResults = coinResults,
                    isSeparateOut = viewModel.isSeparateOutChecked,
                    isMaskOut = viewModel.isMaskOutChecked,
                    speciesBoxes = speciesBoxes,
                    pixelsPerCm = scale,
                    isMarkerDetected = isMarkerDetected
                )
                lastAnalysisResult = analysisResults
                viewPagerAdapter.updateImages(analysisResults)

                updateArrowVisibility(binding.viewpager.currentItem, analysisResults.size)
            }
        }
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

    private fun saveVolumeLog() {
        val bitmapsToSave = if (!lastAnalysisResult.isNullOrEmpty()) {
            lastAnalysisResult!!.map { result ->
                if (result.overlay != null) {
                    val combined = result.original.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(combined)
                    canvas.drawBitmap(result.overlay, 0f, 0f, null)
                    combined
                } else {
                    result.original
                }
            }
        } else if (currentBitmap != null) {
            listOf(currentBitmap!!)
        } else {
            return
        }

        val descriptions = if (!lastAnalysisResult.isNullOrEmpty()) {
            lastAnalysisResult!!.map { it.description }
        } else {
            listOf(getString(R.string.raw_image_marker_status, if (isMarkerDetected) getString(R.string.yes) else getString(R.string.no)))
        }

        val paths = mutableListOf<String>()
        val appContext = requireContext().applicationContext

        try {
            bitmapsToSave.forEachIndexed { index, bitmap ->
                val filename = "vol_${System.currentTimeMillis()}_$index.jpg"
                val file = File(appContext.filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                paths.add(file.absolutePath)
            }

            val combinedPaths = paths.joinToString("|")
            val combinedDetails = descriptions.joinToString(";;;")
            val title = if (isMarkerDetected) getString(R.string.volume_accurate) else getString(R.string.volume_estimated)

            // Check Pre-fetched
            if (capturedLocation != null) {
                performInsert(appContext, combinedPaths, title, combinedDetails, capturedLocation!!.latitude, capturedLocation!!.longitude, "Lat: ${capturedLocation!!.latitude}, Lng: ${capturedLocation!!.longitude}")
                return
            }

            Toast.makeText(appContext, "Acquiring GPS...", Toast.LENGTH_SHORT).show()

            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
                val cancellationTokenSource = CancellationTokenSource()

                // ... inside saveVolumeLog ...
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            // FIX: Use appContext.getString
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
                            // FIX: Use appContext.getString
                            performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, appContext.getString(R.string.location_not_available))
                        }
                    }
                    .addOnFailureListener {
                        // FIX: Use appContext.getString
                        performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, appContext.getString(R.string.location_not_available))
                    }
            } else {
                performInsert(appContext, combinedPaths, title, combinedDetails, 0.0, 0.0, getString(R.string.location_not_available))
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performInsert(context: Context, imagePath: String, title: String, details: String, lat: Double, lng: Double, placeName: String) {
        try {
            val db = DatabaseHelper(context)
            db.insertLog(System.currentTimeMillis(), imagePath, title, details, lat, lng, placeName, DatabaseHelper.TYPE_VOLUME)
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.volume_log_saved), Toast.LENGTH_SHORT).show()
            }
            triggerBackgroundSync(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerBackgroundSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "HistoryUploadWork",
            ExistingWorkPolicy.APPEND,
            syncRequest
        )
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(requireContext())
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogBinding.apply {
            cbSmoothEdges.isChecked = viewModel.isSmoothEdges
            cbSmoothEdges.setOnCheckedChangeListener { _, isChecked -> viewModel.isSmoothEdges = isChecked }

            if (viewModel.useCoinReference) {
                rbCoin.isChecked = true
            } else {
                rbArUco.isChecked = true
            }

            rgReferenceType.setOnCheckedChangeListener { _, checkedId ->
                val isCoin = (checkedId == R.id.rbCoin)
                viewModel.useCoinReference = isCoin

                if (originalBitmap != null) {
                    if (!isCoin) {
                        val (markedBitmap, scale, found) = detectArUcoMarkers(originalBitmap!!)
                        currentBitmap = markedBitmap
                        currentScale = scale
                        isMarkerDetected = found
                        detector?.detect(markedBitmap)
                    } else {
                        currentBitmap = originalBitmap
                        currentScale = 50.0f
                        isMarkerDetected = false
                        detector?.detect(originalBitmap!!)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        if (!isAdded) return
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        currentBitmap?.let { runInstanceSegmentation(it, boundingBoxes, currentScale) }
    }

    override fun onEmptyDetect() {
        currentBitmap?.let { runInstanceSegmentation(it, emptyList(), currentScale) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val seg = instanceSegmentation
        val coinSeg = coinSegmentation
        val det = detector
        _binding = null
        instanceSegmentation = null
        coinSegmentation = null
        detector = null
        lifecycleScope.launch(Dispatchers.IO) {
            segmentationMutex.withLock {
                seg?.close()
                coinSeg?.close()
                det?.close()
            }
        }
    }
}