package com.surendramaran.yolov8tflite.ui

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
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.ml.Detector
import com.surendramaran.yolov8tflite.databinding.FragmentFreshnessBinding
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.surendramaran.yolov8tflite.ml.segmentation.utils.Utils
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FreshnessFragment : Fragment() {

    private var _binding: FragmentFreshnessBinding? = null
    private val binding get() = _binding!!
    private var detectorEyes: Detector? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var cameraExecutor: ExecutorService

    private var tempImageUri: Uri? = null

    private var lastBitmapEyes: Bitmap? = null
    private var lastEyesBoxes: List<BoundingBox> = emptyList()
    private var eyesScore: Float? = null

    // Captured location store
    private var capturedLocation: Location? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { startCrop(it) }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let { startCrop(it) }
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
        dbHelper = DatabaseHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        initDetectors()
        setupButtons()
        loadGifs()
    }

    private fun initDetectors() {
        cameraExecutor.execute {
            context?.let { safeContext ->
                detectorEyes = Detector(safeContext, "eyes_model.tflite", "eyes_labels.txt", object : Detector.DetectorListener {
                    override fun onEmptyDetect() {
                        lastEyesBoxes = emptyList()
                        updateOverlay(emptyList())
                    }
                    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                        val bestBox = boundingBoxes.maxByOrNull { it.cnf }
                        val filteredList = if (bestBox != null) listOf(bestBox) else emptyList()
                        lastEyesBoxes = filteredList
                        updateOverlay(filteredList)
                    }
                })
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
        binding.btnCameraEyes.setOnClickListener {
            checkPermissionAndLaunchCamera()
        }
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
            val tmpFile = File.createTempFile("freshness_temp_", ".jpg", requireContext().cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            tempImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", tmpFile)
            takePictureLauncher.launch(tempImageUri)
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

            if (detectorEyes == null) return
            lastBitmapEyes = bitmap
            binding.imgEyes.setImageBitmap(bitmap)
            binding.imgEyes.visibility = View.VISIBLE
            binding.gifInstructionsEyes.visibility = View.GONE

            binding.overlayEyes.clear()
            binding.overlayEyes.setImageDimensions(bitmap.width, bitmap.height)
            binding.pbEyesLoading.visibility = View.VISIBLE
            cameraExecutor.execute { detectorEyes?.detect(bitmap) }

            // Start Pre-fetch
            capturedLocation = null
            prefetchLocation()

        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_loading_gallery_image), Toast.LENGTH_SHORT).show()
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

    private fun updateOverlay(boxes: List<BoundingBox>) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread

            val overlay = binding.overlayEyes
            val txtResult = binding.txtResultEyes
            val progressBar = binding.pbEyesLoading
            progressBar.visibility = View.GONE

            val bestBox = boxes.maxByOrNull { it.cnf }
            val singleBoxList = if (bestBox != null) listOf(bestBox) else emptyList()

            overlay.setEyeResults(singleBoxList)
            overlay.invalidate()

            if (bestBox != null) {
                val label = bestBox.clsName
                val conf = (bestBox.cnf * 100).toInt()
                txtResult.text = getString(R.string.detected_label, label, conf)

                val isNonFresh = label.lowercase().contains("non") || label.lowercase().contains("spoil")
                val score = if (isNonFresh) 0.5f - (bestBox.cnf / 2.0f) else 0.5f + (bestBox.cnf / 2.0f)

                if (score > 0.5f) {
                    txtResult.setTextColor(Color.parseColor("#1B5E20"))
                    txtResult.background.setTint(Color.parseColor("#E8F5E9"))
                } else {
                    txtResult.setTextColor(Color.parseColor("#B71C1C"))
                    txtResult.background.setTint(Color.parseColor("#FFEBEE"))
                }

                eyesScore = score
                calculateFinalVerdict()
            } else {
                txtResult.text = getString(R.string.no_detection)
                txtResult.background.setTint(Color.parseColor("#F5F5F5"))
                txtResult.setTextColor(Color.parseColor("#757575"))
            }
        }
    }

    private fun calculateFinalVerdict() {
        val eScore = eyesScore

        if (eScore != null) {
            binding.cardFinalVerdict.visibility = View.VISIBLE
            // Use Eye Score directly for final verdict
            val percent = (eScore * 100).toInt()

            if (eScore > 0.5) {
                binding.txtFinalResult.text = getString(R.string.fresh_percentage, percent)
                binding.txtFinalResult.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                binding.txtFinalResult.text = getString(R.string.not_fresh_percentage, percent)
                binding.txtFinalResult.setTextColor(Color.parseColor("#C62828"))
            }
        }
    }

    private fun saveFreshnessLog() {
        val appContext = requireContext().applicationContext // Use App Context

        val paths = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val bitmapsWithBoxes = mutableListOf<Bitmap>()

        lastBitmapEyes?.let { bmp ->
            val drawnBmp = drawBoundingBoxes(appContext, bmp, lastEyesBoxes)
            bitmapsWithBoxes.add(drawnBmp)
            val score = eyesScore
            val status = if (score != null) { if (score > 0.5) "Fresh" else "Not Fresh" } else "Not Analyzed"
            // ADDED: Include Confidence Percentage in details
            val conf = if (score != null) (score * 100).toInt() else 0
            descriptions.add("Part: EYES\nStatus: $status\nConfidence: $conf%")
        }

        if (bitmapsWithBoxes.isEmpty()) return

        try {
            bitmapsWithBoxes.forEachIndexed { index, bitmap ->
                val filename = "fresh_${System.currentTimeMillis()}_$index.jpg"
                val file = File(appContext.filesDir, filename)
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                paths.add(file.absolutePath)
            }
            val combinedPaths = paths.joinToString("|")

            // ADDED: Append Freshness summary for History Graph parsing
            val finalPercent = if(eyesScore != null) (eyesScore!! * 100).toInt() else 0
            val combinedDetails = descriptions.joinToString(";;;") + ";;;Freshness: ${finalPercent}%"

            val title = binding.txtFinalResult.text.toString()

            // Check pre-fetched
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

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performInsert(context: Context, paths: String, title: String, details: String, lat: Double, lng: Double, placeName: String) {
        try {
            val db = DatabaseHelper(context)
            db.insertLog(System.currentTimeMillis(), paths, title, details, lat, lng, placeName, DatabaseHelper.TYPE_FRESHNESS)
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("HistoryUploadWork", ExistingWorkPolicy.APPEND, syncRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun drawBoundingBoxes(context: Context, bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val boxPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.bounding_box_color); style = Paint.Style.STROKE; strokeWidth = 8f }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL }
        boxes.forEach { box ->
            val left = box.x1 * mutableBitmap.width; val top = box.y1 * mutableBitmap.height; val right = box.x2 * mutableBitmap.width; val bottom = box.y2 * mutableBitmap.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${box.clsName}", left, top, textPaint)
        }
        return mutableBitmap
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute {
                detectorEyes?.close()
                detectorEyes = null
            }
            cameraExecutor.shutdown()
        }
        _binding = null
    }
}