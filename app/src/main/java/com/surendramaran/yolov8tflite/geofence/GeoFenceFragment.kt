package com.surendramaran.yolov8tflite.geofence

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.surendramaran.yolov8tflite.R

/**
 * Fragment that displays offline geo-fence protection UI.
 *
 * Shows:
 * - Current maritime zone status (On Land / Territorial / EEZ / Outside)
 * - Distance from coast
 * - GPS coordinates
 * - Legal fishing status
 * - Start/Stop monitoring button
 *
 * All checks are performed 100% OFFLINE using GPS + pre-loaded GeoJSON boundaries.
 */
class GeoFenceFragment : Fragment() {

    // UI elements
    private lateinit var statusEmoji: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var distanceContainer: View
    private lateinit var distanceValue: TextView
    private lateinit var coordinatesValue: TextView
    private lateinit var legalStatus: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var statusCard: View

    private var isMonitoring = false

    // BroadcastReceiver to get real-time location updates from the service
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SafeWatersMonitoringService.BROADCAST_LOCATION_UPDATE) {
                val statusName = intent.getStringExtra(SafeWatersMonitoringService.EXTRA_STATUS) ?: return
                val latitude = intent.getDoubleExtra(SafeWatersMonitoringService.EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(SafeWatersMonitoringService.EXTRA_LONGITUDE, 0.0)
                val distance = intent.getDoubleExtra(SafeWatersMonitoringService.EXTRA_DISTANCE, 0.0)

                val status = try {
                    WaterStatus.valueOf(statusName)
                } catch (e: Exception) {
                    WaterStatus.UNKNOWN
                }

                updateUI(status, latitude, longitude, distance)
            }
        }
    }

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            // Check for background location permission (needed for foreground service on Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    return@registerForActivityResult
                }
            }
            // Check notification permission for Android 13+
            checkNotificationPermissionAndStart()
        } else {
            Toast.makeText(context, "Location permission is required for geo-fence protection", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkNotificationPermissionAndStart()
        } else {
            // Background location denied, but we can still try with foreground
            checkNotificationPermissionAndStart()
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Start monitoring regardless of notification permission
        startMonitoringService()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_geofence, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind views
        statusEmoji = view.findViewById(R.id.status_emoji)
        statusTitle = view.findViewById(R.id.status_title)
        statusSubtitle = view.findViewById(R.id.status_subtitle)
        distanceContainer = view.findViewById(R.id.distance_container)
        distanceValue = view.findViewById(R.id.distance_value)
        coordinatesValue = view.findViewById(R.id.coordinates_value)
        legalStatus = view.findViewById(R.id.legal_status)
        btnToggle = view.findViewById(R.id.btn_toggle_monitoring)
        statusCard = view.findViewById(R.id.status_card)

        // Check if service is already running
        isMonitoring = SafeWatersMonitoringService.isRunning(requireContext())
        updateToggleButton()

        btnToggle.setOnClickListener {
            if (isMonitoring) {
                stopMonitoringService()
            } else {
                requestPermissionsAndStart()
            }
        }

        // If already monitoring, do a one-shot location check to populate UI immediately
        if (isMonitoring) {
            doInitialLocationCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for location updates
        val filter = IntentFilter(SafeWatersMonitoringService.BROADCAST_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(locationReceiver, filter)
        }

        // Update state
        isMonitoring = SafeWatersMonitoringService.isRunning(requireContext())
        updateToggleButton()
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(locationReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    /**
     * Do a one-shot location check using FusedLocationProvider to show
     * the current zone immediately when opening the fragment.
     */
    private fun doInitialLocationCheck() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val fusedClient = com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(requireContext())

                fusedClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null && isAdded) {
                        val checker = MaritimeBoundaryChecker(requireContext())
                        val status = checker.checkLocation(location.latitude, location.longitude)
                        val distance = checker.getDistanceToCoast(location.latitude, location.longitude)
                        updateUI(status, location.latitude, location.longitude, distance)
                    }
                }
            } catch (e: Exception) {
                // Silently fail - service broadcast will update the UI
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    return
                }
            }
            checkNotificationPermissionAndStart()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startMonitoringService()
    }

    private fun startMonitoringService() {
        val intent = Intent(requireContext(), SafeWatersMonitoringService::class.java).apply {
            action = SafeWatersMonitoringService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }

        isMonitoring = true
        updateToggleButton()
        Toast.makeText(context, "🛡️ Border protection started", Toast.LENGTH_SHORT).show()

        // Do initial check
        doInitialLocationCheck()
    }

    private fun stopMonitoringService() {
        val intent = Intent(requireContext(), SafeWatersMonitoringService::class.java).apply {
            action = SafeWatersMonitoringService.ACTION_STOP
        }
        requireContext().startService(intent)

        isMonitoring = false
        updateToggleButton()
        resetUI()
        Toast.makeText(context, "Border protection stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateToggleButton() {
        if (isMonitoring) {
            btnToggle.text = "🛑  Stop Border Protection"
            btnToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        } else {
            btnToggle.text = "🛡️  Start Border Protection"
            btnToggle.setBackgroundColor(0xFF4CAF50.toInt())
        }
    }

    private fun updateUI(status: WaterStatus, latitude: Double, longitude: Double, distance: Double) {
        if (!isAdded) return

        requireActivity().runOnUiThread {
            // Status emoji & title
            statusEmoji.text = status.emoji
            statusTitle.text = status.displayName
            statusSubtitle.text = status.description

            // Color the status card border based on zone
            statusCard.backgroundTintList = android.content.res.ColorStateList.valueOf(
                status.colorHex.toInt()
            )

            // Distance
            distanceContainer.visibility = View.VISIBLE
            distanceValue.text = if (distance < 1.0) {
                String.format("%.0f m", distance * 1000)
            } else {
                String.format("%.1f km", distance)
            }

            // Coordinates
            coordinatesValue.text = String.format("%.4f°, %.4f°", latitude, longitude)

            // Legal status
            legalStatus.visibility = View.VISIBLE
            legalStatus.text = status.legalStatus

            // Color the legal status text based on status
            when (status) {
                WaterStatus.ON_LAND -> {
                    legalStatus.setTextColor(0xFF795548.toInt())
                }
                WaterStatus.TERRITORIAL_WATERS -> {
                    legalStatus.setTextColor(0xFFA5D6A7.toInt())
                }
                WaterStatus.EEZ -> {
                    legalStatus.setTextColor(0xFFFFE082.toInt())
                }
                WaterStatus.OUTSIDE_INDIAN_WATERS -> {
                    legalStatus.setTextColor(0xFFEF9A9A.toInt())
                }
                WaterStatus.UNKNOWN -> {
                    legalStatus.setTextColor(0xFF9E9E9E.toInt())
                }
            }
        }
    }

    private fun resetUI() {
        statusEmoji.text = "⚪"
        statusTitle.text = "Location Unknown"
        statusSubtitle.text = "Enable GPS to check status"
        distanceContainer.visibility = View.GONE
        legalStatus.visibility = View.GONE
    }
}
