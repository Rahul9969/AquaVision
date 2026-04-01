package com.surendramaran.yolov8tflite.ui

import android.Manifest
import android.content.pm.PackageManager // Added this missing import
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.HistoryItem
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapFragment : Fragment() {

    private lateinit var map: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private lateinit var dbHelper: DatabaseHelper
    private var activeMarker: Marker? = null

    // UI Elements for Bottom Card
    private lateinit var detailCard: View
    private lateinit var cardImage: ImageView
    private lateinit var cardTitle: TextView
    private lateinit var cardDate: TextView
    private lateinit var btnCloseCard: ImageView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            setupLocationOverlay()
        } else {
            Toast.makeText(context, getString(R.string.location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())

        // Bind UI
        map = view.findViewById(R.id.map)
        detailCard = view.findViewById(R.id.map_detail_card)
        cardImage = view.findViewById(R.id.card_image)
        cardTitle = view.findViewById(R.id.card_title)
        cardDate = view.findViewById(R.id.card_date)
        btnCloseCard = view.findViewById(R.id.btn_close_card)

        setupMap()
        setupMapInteractions()

        if (checkLocationPermission()) {
            setupLocationOverlay()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        loadOfflineLayers()
        loadSavedDetections()

        view.findViewById<View>(R.id.btn_center_map)?.setOnClickListener {
            val myLoc = locationOverlay?.myLocation
            if (myLoc != null) {
                map.controller.animateTo(myLoc)
                map.controller.setZoom(16.0)
            } else {
                Toast.makeText(context, getString(R.string.waiting_for_gps), Toast.LENGTH_SHORT).show()
            }
        }

        btnCloseCard.setOnClickListener { hideDetailCard() }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.overlayManager.tilesOverlay.isEnabled = true

        // Default View (India)
        val startPoint = GeoPoint(20.5937, 78.9629)
        map.controller.setZoom(5.0)
        map.controller.setCenter(startPoint)
    }

    private fun setupMapInteractions() {
        // Handle clicks on empty map space to close the card
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideDetailCard()
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        val eventsOverlay = MapEventsOverlay(mapEventsReceiver)
        map.overlays.add(0, eventsOverlay) // Add at index 0 to be behind markers
    }

    private fun loadSavedDetections() {
        // 1. Fetch ONLY Detections (Species Captures)
        val detections = dbHelper.getHistoryByType(DatabaseHelper.TYPE_DETECTION)
        val defaultIcon = createSmallDot(Color.RED, 40) // Red dots for detections

        var loadedCount = 0
        var skippedCount = 0

        detections.forEach { item ->
            // Check for valid coordinates
            if (item.lat != 0.0 && item.lng != 0.0) {
                val marker = Marker(map)
                marker.position = GeoPoint(item.lat, item.lng)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                marker.relatedObject = item

                // Custom Icon
                val firstPath = item.imagePath.split("|").firstOrNull() ?: ""
                if (firstPath.isNotEmpty()) {
                    val customIcon = createCircularMarker(firstPath)
                    marker.icon = customIcon ?: defaultIcon
                } else {
                    marker.icon = defaultIcon
                }

                // Click Listener: Show Bottom Card
                marker.setOnMarkerClickListener { m, _ ->
                    activeMarker = m
                    showDetailCard(item)
                    map.controller.animateTo(m.position)
                    true
                }

                map.overlays.add(marker)
                loadedCount++
            } else {
                skippedCount++
            }
        }

        map.invalidate()

        // 2. Feedback
        if (skippedCount > 0) {
            Toast.makeText(context, "Showing $loadedCount captures ($skippedCount hidden due to missing GPS)", Toast.LENGTH_LONG).show()
        } else if (loadedCount > 0) {
            Toast.makeText(context, "$loadedCount captures loaded on map", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No captures with location data found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetailCard(item: HistoryItem) {
        cardTitle.text = item.title.ifEmpty { getString(R.string.unknown) }
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        cardDate.text = sdf.format(Date(item.timestamp))

        val firstPath = item.imagePath.split("|").firstOrNull() ?: ""
        if (File(firstPath).exists()) {
            val bitmap = BitmapFactory.decodeFile(firstPath)
            cardImage.setImageBitmap(bitmap)
        } else {
            cardImage.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // Navigate to Detail Fragment directly
        detailCard.setOnClickListener {
            val bundle = bundleOf(
                "imagePath" to item.imagePath,
                "timestamp" to item.timestamp,
                "fishCount" to item.title,
                "details" to item.details,
                "placeName" to item.placeName,
                "lat" to item.lat.toFloat(),
                "lng" to item.lng.toFloat()
            )
            findNavController().navigate(R.id.historyDetailFragment, bundle)
        }

        if (detailCard.visibility != View.VISIBLE) {
            detailCard.alpha = 0f
            detailCard.translationY = 100f
            detailCard.visibility = View.VISIBLE
            detailCard.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
    }

    private fun hideDetailCard() {
        if (detailCard.visibility == View.VISIBLE) {
            detailCard.animate()
                .alpha(0f)
                .translationY(100f)
                .setDuration(200)
                .withEndAction { detailCard.visibility = View.GONE }
                .start()
            activeMarker = null
        }
    }

    private fun createCircularMarker(path: String): Drawable? {
        try {
            val file = File(path)
            if (!file.exists()) return null

            val options = BitmapFactory.Options()
            options.inSampleSize = 4
            val srcBitmap = BitmapFactory.decodeFile(path, options) ?: return null

            val size = 120
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

            paint.color = Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            val imageSize = size - 12
            val imageBitmap = Bitmap.createScaledBitmap(srcBitmap, imageSize, imageSize, true)

            val shaderBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val shaderCanvas = Canvas(shaderBitmap)
            paint.color = Color.BLACK
            shaderCanvas.drawCircle(size / 2f, size / 2f, imageSize / 2f, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val offset = (size - imageSize) / 2f
            shaderCanvas.drawBitmap(imageBitmap, offset, offset, paint)

            paint.xfermode = null
            canvas.drawBitmap(shaderBitmap, 0f, 0f, paint)

            paint.style = Paint.Style.STROKE
            paint.color = Color.LTGRAY
            paint.strokeWidth = 2f
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, paint)

            return BitmapDrawable(resources, output)
        } catch (e: Exception) {
            return null
        }
    }

    private fun createSmallDot(color: Int, size: Int = 30): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 6, paint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun loadOfflineLayers() {
        try {
            parseGeoJson("indiaeez.json", Color.RED, 2f, true, 0x11FF0000)
            parseGeoJson("sector_new.json", Color.parseColor("#FF5722"), 1.5f, true, 0x22FF5722)
            parseGeoJson("pfz.json", Color.parseColor("#FFC107"), 2f, false)
            loadLandingCenters("landing.json")
        } catch (e: Exception) {
            Log.e("MapFragment", getString(R.string.error_loading_layers), e)
        }
    }

    private fun parseGeoJson(filename: String, color: Int, width: Float, isPolygon: Boolean, fillColor: Int? = null) {
        try {
            val jsonString = requireContext().assets.open(filename).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val type = geometry.getString("type")
                val coordinates = geometry.getJSONArray("coordinates")

                when (type) {
                    "Polygon" -> drawPolygon(coordinates.getJSONArray(0), color, width, fillColor)
                    "MultiPolygon" -> {
                        for (k in 0 until coordinates.length()) {
                            drawPolygon(coordinates.getJSONArray(k).getJSONArray(0), color, width, fillColor)
                        }
                    }
                    "LineString" -> drawLine(coordinates, color, width)
                    "MultiLineString" -> {
                        for (k in 0 until coordinates.length()) {
                            drawLine(coordinates.getJSONArray(k), color, width)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MapFragment", "Error parsing $filename", e)
        }
    }

    private fun drawPolygon(coordArray: JSONArray, strokeColor: Int, width: Float, fillColor: Int?) {
        val points = ArrayList<GeoPoint>()
        for (j in 0 until coordArray.length()) {
            val p = coordArray.getJSONArray(j)
            points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
        }
        val polygon = Polygon()
        polygon.points = points
        polygon.strokeColor = strokeColor
        polygon.strokeWidth = width
        polygon.fillColor = fillColor ?: Color.TRANSPARENT
        map.overlays.add(polygon)
    }

    private fun drawLine(coordArray: JSONArray, color: Int, width: Float) {
        val points = ArrayList<GeoPoint>()
        for (j in 0 until coordArray.length()) {
            val p = coordArray.getJSONArray(j)
            points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
        }
        val line = Polyline()
        line.setPoints(points)
        line.color = color
        line.width = width
        line.paint.strokeCap = Paint.Cap.ROUND
        map.overlays.add(line)
    }

    private fun loadLandingCenters(filename: String) {
        try {
            val jsonString = requireContext().assets.open(filename).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")
            val iconDrawable = createSmallDot(Color.parseColor("#9C27B0"), 25)

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val coord = feature.getJSONObject("geometry").getJSONArray("coordinates")
                val name = feature.optJSONObject("properties")?.optString("name") ?: getString(R.string.port)

                val marker = Marker(map)
                marker.position = GeoPoint(coord.getDouble(1), coord.getDouble(0))
                marker.icon = iconDrawable
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                marker.title = name
                marker.setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    true
                }
                map.overlays.add(marker)
            }
        } catch (e: Exception) {}
    }

    private fun setupLocationOverlay() {
        val provider = GpsMyLocationProvider(requireContext())
        provider.addLocationSource(LocationManager.GPS_PROVIDER)
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER)
        locationOverlay = MyLocationNewOverlay(provider, map)

        val personBitmap = BitmapFactory.decodeResource(resources, org.osmdroid.library.R.drawable.person)
        if(personBitmap != null) locationOverlay?.setPersonIcon(personBitmap)

        locationOverlay?.enableMyLocation()
        locationOverlay?.enableFollowLocation()
        map.overlays.add(locationOverlay)
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() { super.onResume(); map.onResume(); locationOverlay?.enableMyLocation(); loadSavedDetections() }
    override fun onPause() { super.onPause(); map.onPause(); locationOverlay?.disableMyLocation() }
}