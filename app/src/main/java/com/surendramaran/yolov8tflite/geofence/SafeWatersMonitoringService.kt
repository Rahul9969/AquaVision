package com.surendramaran.yolov8tflite.geofence

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.surendramaran.yolov8tflite.MainActivity
import com.surendramaran.yolov8tflite.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service that continuously monitors the fisherman's GPS location
 * and checks it against India's maritime boundaries OFFLINE.
 *
 * Features:
 * - Persistent notification showing current maritime zone
 * - Alert notifications when crossing zone boundaries
 * - Critical vibration + alarm when entering international waters
 * - Works 100% OFFLINE using pre-loaded GeoJSON boundary data
 * - Restarts automatically if killed by the system
 */
class SafeWatersMonitoringService : Service() {

    companion object {
        private const val TAG = "SafeWatersService"

        // Notification IDs
        private const val PERSISTENT_NOTIFICATION_ID = 2001
        private const val ALERT_NOTIFICATION_BASE_ID = 3000

        // Channel IDs
        private const val CHANNEL_PERSISTENT = "safewaters_monitoring"
        private const val CHANNEL_ALERTS = "safewaters_alerts"

        // Actions
        const val ACTION_START = "com.surendramaran.yolov8tflite.START_SAFEWATERS"
        const val ACTION_STOP = "com.surendramaran.yolov8tflite.STOP_SAFEWATERS"

        // Location update interval
        private const val UPDATE_INTERVAL = 10000L        // 10 seconds
        private const val FASTEST_INTERVAL = 5000L        // 5 seconds minimum

        // Broadcast action for UI updates
        const val BROADCAST_LOCATION_UPDATE = "com.surendramaran.yolov8tflite.LOCATION_UPDATE"
        const val EXTRA_STATUS = "water_status"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_DISTANCE = "distance_from_coast"

        // SharedPrefs key for service running state
        private const val PREFS_NAME = "safewaters_prefs"
        private const val KEY_IS_RUNNING = "is_monitoring"

        fun isRunning(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_IS_RUNNING, false)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var boundaryChecker: MaritimeBoundaryChecker
    private lateinit var notificationManager: NotificationManager

    // State tracking
    private var currentStatus: WaterStatus = WaterStatus.UNKNOWN
    private var previousStatus: WaterStatus = WaterStatus.UNKNOWN
    private var lastLocation: Location? = null
    private var distanceFromCoast: Double = 0.0
    private var serviceStartTime: Long = 0L
    private var locationUpdateCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            boundaryChecker = MaritimeBoundaryChecker(this)
            Log.d(TAG, "Boundary checker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize boundary checker", e)
            stopSelf()
            return
        }

        createNotificationChannels()
        setupLocationCallback()
        serviceStartTime = System.currentTimeMillis()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    locationUpdateCount++
                    Log.d(TAG, "Location update #$locationUpdateCount: ${location.latitude}, ${location.longitude}")
                    handleLocationUpdate(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting SafeWaters monitoring")
                setRunningState(true)
                startMonitoring()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping SafeWaters monitoring")
                setRunningState(false)
                stopMonitoring()
            }
        }
        return START_STICKY
    }

    private fun setRunningState(running: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_RUNNING, running)
            .apply()
    }

    private fun startMonitoring() {
        startForeground(PERSISTENT_NOTIFICATION_ID, createInitialNotification())

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            setWaitForAccurateLocation(false)
            setMaxUpdateDelayMillis(UPDATE_INTERVAL * 2)
        }.build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested")
        } else {
            Log.e(TAG, "Location permission not granted")
            stopMonitoring()
        }
    }

    private fun stopMonitoring() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Service stopped")
    }

    private fun handleLocationUpdate(location: Location) {
        lastLocation = location
        previousStatus = currentStatus

        // Check maritime status (100% OFFLINE)
        currentStatus = boundaryChecker.checkLocation(location.latitude, location.longitude)

        // Calculate distance from coast
        distanceFromCoast = boundaryChecker.getDistanceToCoast(location.latitude, location.longitude)

        Log.d(TAG, "Status: $currentStatus, Distance: ${distanceFromCoast}km")

        // Send broadcast for UI updates
        sendLocationBroadcast(location)

        // Send alert if status changed (and it's not the first update)
        if (currentStatus != previousStatus && previousStatus != WaterStatus.UNKNOWN) {
            Log.d(TAG, "Zone changed: $previousStatus → $currentStatus")
            sendZoneChangeAlert(previousStatus, currentStatus)

            // Extra vibration for critical alerts
            if (currentStatus == WaterStatus.OUTSIDE_INDIAN_WATERS) {
                triggerEmergencyVibration()
            }
        }

        // Update persistent notification
        updatePersistentNotification()
    }

    private fun sendLocationBroadcast(location: Location) {
        val intent = Intent(BROADCAST_LOCATION_UPDATE).apply {
            putExtra(EXTRA_STATUS, currentStatus.name)
            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
            putExtra(EXTRA_DISTANCE, distanceFromCoast)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun triggerEmergencyVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 2000),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 2000),
                        -1
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    // ==================== NOTIFICATIONS ====================

    private fun createInitialNotification(): Notification {
        val pendingIntent = createPendingIntent()

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle("🌊 SafeWaters Monitor Starting")
            .setContentText("Getting your location...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updatePersistentNotification() {
        val notification = buildPersistentNotification()
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    private fun buildPersistentNotification(): Notification {
        val pendingIntent = createPendingIntent()
        val (title, message, priority) = getPersistentNotificationContent()

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(priority)
            .setColor(currentStatus.colorHex.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun getPersistentNotificationContent(): Triple<String, String, Int> {
        val distanceText = if (distanceFromCoast < 1.0) {
            String.format("%.0f m", distanceFromCoast * 1000)
        } else {
            String.format("%.1f km", distanceFromCoast)
        }

        val timeRunning = (System.currentTimeMillis() - serviceStartTime) / 1000 / 60

        return when (currentStatus) {
            WaterStatus.ON_LAND -> Triple(
                "${currentStatus.emoji} On Land",
                "You are on land • $distanceText from coast\nMonitoring for $timeRunning min",
                NotificationCompat.PRIORITY_LOW
            )

            WaterStatus.TERRITORIAL_WATERS -> {
                val withinReservedZone = distanceFromCoast < 5.0
                if (withinReservedZone) {
                    Triple(
                        "${currentStatus.emoji} Reserved Zone ($distanceText)",
                        "✅ TRADITIONAL CRAFT ONLY\n⚠️ Mechanized boats: Move beyond 5 km",
                        NotificationCompat.PRIORITY_HIGH
                    )
                } else {
                    Triple(
                        "${currentStatus.emoji} Territorial Waters ($distanceText)",
                        "✅ SAFE FISHING ZONE\nAll licensed fishermen allowed • $locationUpdateCount updates",
                        NotificationCompat.PRIORITY_DEFAULT
                    )
                }
            }

            WaterStatus.EEZ -> Triple(
                "${currentStatus.emoji} Indian EEZ ($distanceText)",
                "✅ LEGAL with Access Pass\nExclusive Economic Zone • Monitored for $timeRunning min",
                NotificationCompat.PRIORITY_DEFAULT
            )

            WaterStatus.OUTSIDE_INDIAN_WATERS -> Triple(
                "${currentStatus.emoji} ⚠️ OUTSIDE India ($distanceText)",
                "🚨 INTERNATIONAL WATERS\nMost vessels NOT authorized here! TURN BACK!",
                NotificationCompat.PRIORITY_MAX
            )

            WaterStatus.UNKNOWN -> Triple(
                "${currentStatus.emoji} Getting Location",
                "Waiting for GPS signal... ($locationUpdateCount attempts)",
                NotificationCompat.PRIORITY_LOW
            )
        }
    }

    private fun sendZoneChangeAlert(oldStatus: WaterStatus, newStatus: WaterStatus) {
        val notificationId = ALERT_NOTIFICATION_BASE_ID + System.currentTimeMillis().toInt() % 1000

        val (title, message, priority, shouldVibrate) = getAlertContent(oldStatus, newStatus)

        val pendingIntent = createPendingIntent()

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .setColor(newStatus.colorHex.toInt())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (shouldVibrate) {
            builder.setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
        }

        // For critical warnings, add sound
        if (priority == NotificationCompat.PRIORITY_MAX) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            val actionIntent = createPendingIntent()
            builder.addAction(
                R.drawable.ic_launcher_foreground,
                "Open App",
                actionIntent
            )
        }

        notificationManager.notify(notificationId, builder.build())
        Log.d(TAG, "Alert sent: $title")
    }

    private fun getAlertContent(
        oldStatus: WaterStatus,
        newStatus: WaterStatus
    ): AlertData {
        val distanceText = String.format("%.1f km", distanceFromCoast)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        return when {
            // CRITICAL: Entering international waters
            newStatus == WaterStatus.OUTSIDE_INDIAN_WATERS -> AlertData(
                "🚨 CRITICAL: Leaving Indian Waters!",
                "⚠️ You are now in INTERNATIONAL WATERS ($distanceText from coast)\n\n" +
                        "❌ Most Indian fishing vessels are NOT authorized here\n" +
                        "🔄 TURN BACK IMMEDIATELY to avoid penalties\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_MAX,
                true
            )

            // WARNING: Mechanized boats in reserved zone (< 5 km)
            newStatus == WaterStatus.TERRITORIAL_WATERS &&
                    distanceFromCoast < 5.0 &&
                    oldStatus != WaterStatus.ON_LAND -> AlertData(
                "⚠️ WARNING: Reserved Zone!",
                "🚫 You are $distanceText from coast\n\n" +
                        "This zone is RESERVED for traditional craft only\n" +
                        "⚠️ Mechanized boats must move beyond 5 km\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_MAX,
                true
            )

            // ALERT: Moving from territorial to EEZ
            oldStatus == WaterStatus.TERRITORIAL_WATERS &&
                    newStatus == WaterStatus.EEZ -> AlertData(
                "📍 Entering EEZ",
                "✅ You've entered India's Exclusive Economic Zone\n\n" +
                        "Distance from coast: $distanceText\n" +
                        "⚠️ Access Pass required for mechanized vessels\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_HIGH,
                false
            )

            // INFO: Returning from EEZ to territorial
            oldStatus == WaterStatus.EEZ &&
                    newStatus == WaterStatus.TERRITORIAL_WATERS -> AlertData(
                "📍 Returning to Territorial Waters",
                "✅ You've entered territorial waters (0-12 NM)\n\n" +
                        "Distance from coast: $distanceText\n" +
                        "All licensed Indian fishermen allowed\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_DEFAULT,
                false
            )

            // RELIEF: Returning from outside to EEZ
            oldStatus == WaterStatus.OUTSIDE_INDIAN_WATERS &&
                    newStatus == WaterStatus.EEZ -> AlertData(
                "✅ Back in Indian Waters",
                "🎉 You've re-entered India's EEZ\n\n" +
                        "Distance from coast: $distanceText\n" +
                        "You are now in authorized fishing zone\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_HIGH,
                false
            )

            // RELIEF: Returning to safety from outside
            oldStatus == WaterStatus.OUTSIDE_INDIAN_WATERS &&
                    newStatus == WaterStatus.TERRITORIAL_WATERS -> AlertData(
                "✅ Safe Zone Reached",
                "🎉 You've returned to territorial waters\n\n" +
                        "Distance from coast: $distanceText\n" +
                        "You are now in a safe, legal fishing zone\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_HIGH,
                false
            )

            // Default: Generic zone change
            else -> AlertData(
                "📍 Zone Changed",
                "Zone: ${oldStatus.displayName} → ${newStatus.displayName}\n\n" +
                        "Current distance: $distanceText from coast\n" +
                        "Status: ${newStatus.description}\n\n" +
                        "Time: $timestamp",
                NotificationCompat.PRIORITY_DEFAULT,
                false
            )
        }
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Persistent monitoring channel
            val persistentChannel = NotificationChannel(
                CHANNEL_PERSISTENT,
                "SafeWaters Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows your current maritime zone status continuously"
                enableVibration(false)
                setShowBadge(false)
            }

            // Alert channel (for zone changes)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "SafeWaters Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important alerts when you change maritime zones"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
                enableLights(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            notificationManager.createNotificationChannel(persistentChannel)
            notificationManager.createNotificationChannel(alertChannel)
            Log.d(TAG, "Notification channels created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        setRunningState(false)
        Log.d(TAG, "Service destroyed - ran for ${(System.currentTimeMillis() - serviceStartTime) / 1000}s")
    }
}

/** Helper data class for alert notification content */
data class AlertData(
    val title: String,
    val message: String,
    val priority: Int,
    val shouldVibrate: Boolean
)
