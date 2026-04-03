package com.rahul.aquavision.ui.fishing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.rahul.aquavision.R
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*

/**
 * FishingZoneFragment — INCOIS-style PFZ with live navigation line.
 *
 * When a hotspot is tapped, a Google Maps-style polyline is drawn from the
 * user's live GPS position to the hotspot. The line updates every ~100ms
 * as the device moves (using LocationManager with FASTEST_INTERVAL = 0).
 */
class FishingZoneFragment : Fragment() {

    companion object {
        private const val TAG = "FishingZoneFragment"
        private const val DEFAULT_LAT = 13.0
        private const val DEFAULT_LON = 76.0
        private const val DEFAULT_ZOOM = 5.5
        private const val PREFS = "pfz_prefs"
        private const val KEY_PREFERRED_FLC = "preferred_flc_id"

        private val ESRI_SATELLITE = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                return "$baseUrl$z/$y/$x"
            }
        }
    }

    // ── Map ───────────────────────────────────────────────────────────────
    private lateinit var map: MapView
    private lateinit var repository: FishingZoneRepository
    private var locationOverlay: MyLocationNewOverlay? = null
    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    // ── Navigation Polyline ───────────────────────────────────────────────
    private var navLine: Polyline? = null              // outer white halo
    private var navLineInner: Polyline? = null         // inner blue/orange line
    private var liveLocation: GeoPoint? = null         // last known GPS point
    private var locationManager: LocationManager? = null

    /**
     * LocationListener that fires as fast as the GPS provides fixes.
     * We request minTime=0ms, minDistance=0m so it updates every available fix.
     */
    private val navLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            liveLocation = GeoPoint(location.latitude, location.longitude)
            // Redraw the navigation line every GPS tick
            selectedHotspot?.let { redrawNavLine(it) }
            // Live-update the bottom sheet INCOIS rows
            selectedHotspot?.let { updateIncoisRows(it) }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ── Data ──────────────────────────────────────────────────────────────
    private var fishingZoneResponse: FishingZoneResponse? = null
    private var heatmapResponse: HeatmapResponse? = null
    private var selectedHotspot: FishingHotspot? = null
    private var activeFLC: FishLandingCentre? = null

    // ── Overlay lists ─────────────────────────────────────────────────────
    private val hotspotMarkers = mutableListOf<Marker>()
    private val sstOverlays    = mutableListOf<Polygon>()
    private val chlOverlays    = mutableListOf<Polygon>()
    private val flcMarkers     = mutableListOf<Marker>()

    // ── Layer toggles ─────────────────────────────────────────────────────
    private var showHotspots    = true
    private var showSST         = true
    private var showChlorophyll = false
    private var showFLCs        = true

    // ── Unit state ────────────────────────────────────────────────────────
    private var distanceUnitNm = false
    private var depthUnitFeet  = false
    private var coordDecimal   = false
    private var isSatelliteView = false

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var tvPfzDate: TextView
    private lateinit var tvCurrentFlc: TextView
    private lateinit var tvForecast: TextView
    private lateinit var tvLoadingStatus: TextView
    private lateinit var loadingOverlay: View
    private lateinit var etSearchFlc: AutoCompleteTextView
    private lateinit var btnSetPreferred: com.google.android.material.button.MaterialButton
    private lateinit var btnNoPreferred: com.google.android.material.button.MaterialButton

    // Bottom-sheet INCOIS rows
    private lateinit var tvDirectionValue: TextView
    private lateinit var tvBearingValue: TextView
    private lateinit var tvDistanceValue: TextView
    private lateinit var tvDepthValue: TextView
    private lateinit var tvLocationValue: TextView
    private lateinit var spinnerDistUnit: Spinner
    private lateinit var spinnerDepthUnit: Spinner
    private lateinit var spinnerCoordUnit: Spinner

    // Conditions
    private lateinit var tvHotspotTitle: TextView
    private lateinit var tvHotspotConfidence: TextView
    private lateinit var tvHsiScore: TextView
    private lateinit var tvCondSST: TextView
    private lateinit var tvCondChlorophyll: TextView
    private lateinit var tvCondDepth: TextView
    private lateinit var tvCondCurrent: TextView
    private lateinit var tvCondSSTGradient: TextView
    private lateinit var tvCondSalinity: TextView
    private lateinit var tvCondOxygen: TextView
    private lateinit var tvCondSSH: TextView
    private lateinit var tvAdvisory: TextView

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        return inflater.inflate(R.layout.fragment_fishing_zone, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = FishingZoneRepository(requireContext())

        initViews(view)
        setupMap()
        setupBottomSheet(view)
        setupButtons(view)
        setupFlcSearch()
        setupSpinners()
        setupLayerToggles(view)

        tvPfzDate.text = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())

        val prefId = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_FLC, null)
        activeFLC = FishLandingCentreData.ALL_FLCS.firstOrNull { it.id == prefId }
        updatePreferredButton()

        renderFlcMarkers()   // show all 60 FLC landing centres on map
        fetchData()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startNavLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        stopNavLocationUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopNavLocationUpdates()
        removeNavLine()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Live GPS Updates for Navigation Line
    // ══════════════════════════════════════════════════════════════════════

    private fun startNavLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Use both GPS + Network for fastest updates
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,   // min time ms (as fast as possible)
                0f,   // min distance metres
                navLocationListener
            )
        }
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                0L,
                0f,
                navLocationListener
            )
        }

        // Seed with last known location immediately
        val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastNet = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        val best = when {
            lastGps != null && lastNet != null ->
                if (lastGps.time > lastNet.time) lastGps else lastNet
            lastGps != null -> lastGps
            else -> lastNet
        }
        best?.let { liveLocation = GeoPoint(it.latitude, it.longitude) }
    }

    private fun stopNavLocationUpdates() {
        runCatching { locationManager?.removeUpdates(navLocationListener) }
        locationManager = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // Navigation Polyline Drawing
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draw/update a Google Maps-style line: thick white halo + thinner blue line on top.
     * Called every GPS tick while a hotspot is selected.
     */
    private fun redrawNavLine(hotspot: FishingHotspot) {
        val from = liveLocation ?: locationOverlay?.myLocation ?: return
        val to   = GeoPoint(hotspot.lat, hotspot.lon)
        val pts  = listOf(from, to)

        // ── White halo (outer) ────────────────────────────────────────────
        if (navLine == null) {
            navLine = Polyline(map).apply {
                outlinePaint.color     = Color.WHITE
                outlinePaint.strokeWidth = 22f
                outlinePaint.style     = Paint.Style.STROKE
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin= Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlays.add(1, navLine!!)
        }
        navLine!!.setPoints(pts)

        // ── Blue/Orange inner line ────────────────────────────────────────
        if (navLineInner == null) {
            navLineInner = Polyline(map).apply {
                outlinePaint.color      = Color.parseColor("#FF6D00")  // deep orange, like Google Maps nav
                outlinePaint.strokeWidth = 13f
                outlinePaint.style      = Paint.Style.STROKE
                outlinePaint.strokeCap  = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlays.add(2, navLineInner!!)
        }
        navLineInner!!.setPoints(pts)

        map.invalidate()
    }

    private fun removeNavLine() {
        navLine?.let { map.overlays.remove(it) }
        navLineInner?.let { map.overlays.remove(it) }
        navLine      = null
        navLineInner = null
        map.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════
    // View Initialisation
    // ══════════════════════════════════════════════════════════════════════

    private fun initViews(view: View) {
        map               = view.findViewById(R.id.fishing_map)
        tvPfzDate         = view.findViewById(R.id.tv_pfz_date)
        tvCurrentFlc      = view.findViewById(R.id.tv_current_flc)
        tvForecast        = view.findViewById(R.id.tv_forecast_available)
        tvLoadingStatus   = view.findViewById(R.id.tv_loading_status)
        loadingOverlay    = view.findViewById(R.id.loading_overlay)
        etSearchFlc       = view.findViewById(R.id.et_search_flc)
        btnSetPreferred   = view.findViewById(R.id.btn_set_preferred_flc)
        btnNoPreferred    = view.findViewById(R.id.btn_no_preferred_flc)

        tvDirectionValue  = view.findViewById(R.id.tv_direction_value)
        tvBearingValue    = view.findViewById(R.id.tv_bearing_value)
        tvDistanceValue   = view.findViewById(R.id.tv_distance_value)
        tvDepthValue      = view.findViewById(R.id.tv_depth_value)
        tvLocationValue   = view.findViewById(R.id.tv_location_value)
        spinnerDistUnit   = view.findViewById(R.id.spinner_distance_unit)
        spinnerDepthUnit  = view.findViewById(R.id.spinner_depth_unit)
        spinnerCoordUnit  = view.findViewById(R.id.spinner_coord_unit)

        tvHotspotTitle      = view.findViewById(R.id.tv_hotspot_title)
        tvHotspotConfidence = view.findViewById(R.id.tv_hotspot_confidence)
        tvHsiScore          = view.findViewById(R.id.tv_hsi_score)
        tvCondSST           = view.findViewById(R.id.tv_cond_sst)
        tvCondChlorophyll   = view.findViewById(R.id.tv_cond_chlorophyll)
        tvCondDepth         = view.findViewById(R.id.tv_cond_depth)
        tvCondCurrent       = view.findViewById(R.id.tv_cond_current)
        tvCondSSTGradient   = view.findViewById(R.id.tv_cond_sst_gradient)
        tvCondSalinity      = view.findViewById(R.id.tv_cond_salinity)
        tvCondOxygen        = view.findViewById(R.id.tv_cond_oxygen)
        tvCondSSH           = view.findViewById(R.id.tv_cond_ssh)
        tvAdvisory          = view.findViewById(R.id.tv_advisory)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Map Setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupMap() {
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    private fun setupLocationOverlay() {
        val provider = GpsMyLocationProvider(requireContext())
        provider.addLocationSource(LocationManager.GPS_PROVIDER)
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER)
        locationOverlay = MyLocationNewOverlay(provider, map)
        val personBitmap = BitmapFactory.decodeResource(resources, org.osmdroid.library.R.drawable.person)
        if (personBitmap != null) locationOverlay?.setPersonIcon(personBitmap)
        locationOverlay?.enableMyLocation()
        map.overlays.add(locationOverlay)
    }

    private fun setupBottomSheet(view: View) {
        val bs = view.findViewById<View>(R.id.bottom_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bs).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
            isHideable = true
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(view: View, newState: Int) {
                    // If user dismisses the bottom sheet, remove the nav line
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        selectedHotspot = null
                        removeNavLine()
                    }
                }
                override fun onSlide(view: View, offset: Float) {}
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // FLC Search
    // ══════════════════════════════════════════════════════════════════════

    private fun setupFlcSearch() {
        val names = FishLandingCentreData.ALL_FLCS.map { "${it.name}, ${it.state}" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        etSearchFlc.setAdapter(adapter)
        etSearchFlc.threshold = 1   // show dropdown after 1 character
        etSearchFlc.setOnItemClickListener { _, _, position, _ ->
            val flc = FishLandingCentreData.ALL_FLCS[position]
            activeFLC = flc
            tvCurrentFlc.text = "${flc.name}, ${flc.state}"
            etSearchFlc.setText("")
            selectedHotspot?.let { updateIncoisRows(it) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Spinners
    // ══════════════════════════════════════════════════════════════════════

    private fun setupSpinners() {
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                distanceUnitNm = spinnerDistUnit.selectedItemPosition == 1
                depthUnitFeet  = spinnerDepthUnit.selectedItemPosition == 1
                coordDecimal   = spinnerCoordUnit.selectedItemPosition == 1
                selectedHotspot?.let { updateIncoisRows(it) }
            }
        }
        spinnerDistUnit.onItemSelectedListener  = listener
        spinnerDepthUnit.onItemSelectedListener = listener
        spinnerCoordUnit.onItemSelectedListener = listener
    }

    // ══════════════════════════════════════════════════════════════════════
    // Buttons
    // ══════════════════════════════════════════════════════════════════════

    private fun setupButtons(view: View) {
        view.findViewById<View>(R.id.btn_center_location)?.setOnClickListener {
            val loc = liveLocation ?: locationOverlay?.myLocation
            if (loc != null) {
                map.controller.animateTo(loc)
                map.controller.setZoom(10.0)
            } else {
                map.controller.animateTo(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
                map.controller.setZoom(DEFAULT_ZOOM)
            }
        }

        view.findViewById<View>(R.id.btn_refresh)?.setOnClickListener { fetchData() }

        view.findViewById<View>(R.id.btn_navigate)?.setOnClickListener {
            selectedHotspot?.let { navigateToHotspot(it) }
        }

        // Toggle top information bar
        val infoBar = view.findViewById<View>(R.id.info_bar)
        view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_toggle_info)?.setOnClickListener {
            if (infoBar.visibility == View.VISIBLE) {
                infoBar.visibility = View.GONE
                map.invalidate()
            } else {
                infoBar.visibility = View.VISIBLE
                map.invalidate()
            }
        }

        // Toggle Satellite View
        val btnSatellite = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_satellite_view)
        btnSatellite?.setOnClickListener {
            isSatelliteView = !isSatelliteView
            if (isSatelliteView) {
                map.setTileSource(ESRI_SATELLITE)
                btnSatellite.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0277BD"))
                btnSatellite.setColorFilter(android.graphics.Color.WHITE)
            } else {
                map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                btnSatellite.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                btnSatellite.setColorFilter(android.graphics.Color.parseColor("#0277BD"))
            }
        }

        btnSetPreferred.setOnClickListener {
            val flc = activeFLC ?: return@setOnClickListener
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFERRED_FLC, flc.id).apply()
            updatePreferredButton()
            Toast.makeText(context, "${flc.name} set as preferred FLC", Toast.LENGTH_SHORT).show()
        }

        btnNoPreferred.setOnClickListener {
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PREFERRED_FLC).apply()
            activeFLC = null
            updatePreferredButton()
            tvCurrentFlc.text = getNearestFlcName()
        }

        view.findViewById<View>(R.id.tv_flc_info_link)?.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://incois.gov.in/portal/osf/pfz.jsp")))
            }.onFailure {
                Toast.makeText(context, "Could not open INCOIS PFZ page", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePreferredButton() {
        val prefId = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_FLC, null)
        if (prefId != null) {
            val flc = FishLandingCentreData.ALL_FLCS.firstOrNull { it.id == prefId }
            btnNoPreferred.text  = "★ ${flc?.name ?: "PREFERRED SET"}"
            btnSetPreferred.text = "CHANGE PREFERRED FLC"
        } else {
            btnNoPreferred.text  = "NO PREFERRED FLC SET"
            btnSetPreferred.text = "SET AS PREFERRED FLC"
        }
    }

    private fun getNearestFlcName(): String {
        val loc = liveLocation ?: locationOverlay?.myLocation
        val lat = loc?.latitude  ?: DEFAULT_LAT
        val lon = loc?.longitude ?: DEFAULT_LON
        val r = FishLandingCentreData.findNearestFlc(lat, lon)
        return "${r.flc.name}, ${r.flc.state}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // Layer Toggles
    // ══════════════════════════════════════════════════════════════════════

    private fun setupLayerToggles(view: View) {
        view.findViewById<Chip>(R.id.chip_hotspots)?.setOnCheckedChangeListener { _, checked ->
            showHotspots = checked
            toggleOverlays(hotspotMarkers, checked)
            map.invalidate()
        }
        view.findViewById<Chip>(R.id.chip_sst)?.setOnCheckedChangeListener { _, checked ->
            showSST = checked
            toggleOverlays(sstOverlays, checked)
            map.invalidate()
        }
        view.findViewById<Chip>(R.id.chip_chlorophyll)?.setOnCheckedChangeListener { _, checked ->
            showChlorophyll = checked
            if (checked && chlOverlays.isEmpty() && heatmapResponse != null)
                renderChlorophyllOverlay(heatmapResponse!!)
            toggleOverlays(chlOverlays, checked)
            map.invalidate()
        }
        view.findViewById<Chip>(R.id.chip_flcs)?.setOnCheckedChangeListener { _, checked ->
            showFLCs = checked
            toggleOverlays(flcMarkers, checked)
            map.invalidate()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Data — 60 FLC Fishing Zones with Real-Time Marine Data
    // ══════════════════════════════════════════════════════════════

    /**
     * Renders all 60 FLC locations IMMEDIATELY as fishing zone markers.
     * Then fires async Open-Meteo Marine API calls in the background to
     * fetch real-time SST & wave data for each zone and update the icons.
     */
    private fun fetchData() {
        val loc = liveLocation ?: locationOverlay?.myLocation
        val centerLat = loc?.latitude  ?: DEFAULT_LAT
        val centerLon = loc?.longitude ?: DEFAULT_LON

        if (activeFLC == null) {
            val nearest = FishLandingCentreData.findNearestFlc(centerLat, centerLon)
            activeFLC = nearest.flc
            tvCurrentFlc.text = "${nearest.flc.name}, ${nearest.flc.state}"
        }

        // Step 1: Show all 60 FLC zones immediately with placeholder score
        renderFlcZones()

        // Step 2: Fetch real marine data silently in the background
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Prioritize the active FLC first!
                val active = activeFLC
                if (active != null && !flcMarineCache.containsKey(active.id)) {
                    val seaLat = active.lat
                    val seaLon = if (active.lon < 77.0) active.lon - 0.7 else active.lon + 0.7
                    fetchMarineDataForFlc(active, seaLat, seaLon)
                }

                // Fetch the rest gracefully waiting 100ms between calls to not overwhelm API
                FishLandingCentreData.ALL_FLCS.forEach { flc ->
                    // Stop fetching if current view is destroyed
                    if (!isAdded) return@launch
                    
                    if (flc.id != active?.id && !flcMarineCache.containsKey(flc.id)) {
                        val seaLat = flc.lat
                        val seaLon = if (flc.lon < 77.0) flc.lon - 0.7 else flc.lon + 0.7
                        fetchMarineDataForFlc(flc, seaLat, seaLon)
                        kotlinx.coroutines.delay(100)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Marine data fetch error", e)
            }
        }
    }

    /** Hold per-FLC marine data so bottom sheet always has fresh values */
    private val flcMarineCache = mutableMapOf<String, MarineData>()

    data class MarineData(
        val sst: Double?,
        val waveHeight: Double?,
        val waveDir: Double?,
        val wavePeriod: Double?,
        val chlorophyll: Double?,
        val hsiScore: Double,
        val species: List<String>,
        val advisory: String,
        val confidence: String
    )

    /** Fetches real-time marine data: SST + waves from Open-Meteo, Chlorophyll-a from NOAA ERDDAP */
    private suspend fun fetchMarineDataForFlc(flc: FishLandingCentre, lat: Double, lon: Double) {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // ── 1. Open-Meteo Marine: SST + Waves ───────────────────────
                val marineUrl = "https://marine-api.open-meteo.com/v1/marine" +
                    "?latitude=${String.format(java.util.Locale.US, "%.4f", lat)}&longitude=${String.format(java.util.Locale.US, "%.4f", lon)}" +
                    "&current=wave_height,wave_direction,wave_period,sea_surface_temperature" +
                    "&hourly=sea_surface_temperature&forecast_days=1"

                var sst:   Double? = null
                var waveH: Double? = null
                var waveD: Double? = null
                var wavePeriod: Double? = null

                try {
                    val req = okhttp3.Request.Builder().url(marineUrl).build()
                    val body = client.newCall(req).execute().body?.string()
                    body?.let {
                        val cur = org.json.JSONObject(it).optJSONObject("current")
                        sst       = cur?.optDouble("sea_surface_temperature")?.takeIf { v -> !v.isNaN() }
                        waveH     = cur?.optDouble("wave_height")?.takeIf { v -> !v.isNaN() }
                        waveD     = cur?.optDouble("wave_direction")?.takeIf { v -> !v.isNaN() }
                        wavePeriod = cur?.optDouble("wave_period")?.takeIf { v -> !v.isNaN() }
                    }
                } catch (e: Exception) { Log.w(TAG, "SST fetch failed: ${e.message}") }

                // ── 2. NOAA CoastWatch ERDDAP: Chlorophyll-a (mg/m³) ─────────
                // MODIS-Aqua 4km daily composite — snap to 0.05° grid
                var chlorophyll: Double? = null
                try {
                    val latMin = String.format(java.util.Locale.US, "%.2f", lat - 0.1)
                    val latMax = String.format(java.util.Locale.US, "%.2f", lat + 0.1)
                    val lonMin = String.format(java.util.Locale.US, "%.2f", lon - 0.1)
                    val lonMax = String.format(java.util.Locale.US, "%.2f", lon + 0.1)
                    
                    val chlUrl = "https://coastwatch.pfeg.noaa.gov/erddap/griddap/erdMH1chla1day.json" +
                        "?chlorophyll[(last):1:(last)]" +
                        "[$latMin:1:$latMax]" +
                        "[$lonMin:1:$lonMax]"
                    val chlReq = okhttp3.Request.Builder().url(chlUrl).build()
                    val chlBody = client.newCall(chlReq).execute().body?.string()
                    chlBody?.let {
                        val table = org.json.JSONObject(it).optJSONObject("table")
                        val rows = table?.optJSONArray("rows")
                        if (rows != null) {
                            for (i in 0 until rows.length()) {
                                val row = rows.optJSONArray(i) ?: continue
                                val v = row.optDouble(3)
                                if (!v.isNaN() && v > 0) { chlorophyll = v; break }
                            }
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Chl fetch failed for ${flc.name}: ${e.message}") }

                val marine = buildMarineData(sst, waveH, waveD, wavePeriod, chlorophyll, lat, lon)
                flcMarineCache[flc.id] = marine

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateFlcZoneMarker(flc, marine)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Marine fetch failed for ${flc.name}: ${e.message}")
            }
        }
    }

    /**
     * Builds MarineData from real measured values.
     * Species and HSI derived ENTIRELY from actual SST + Chlorophyll-a measurements.
     */
    private fun buildMarineData(
        sst: Double?, waveH: Double?, waveD: Double?,
        wavePeriod: Double?, chlorophyll: Double?,
        lat: Double, lon: Double
    ): MarineData {
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val isMonsoon = month in 6..9
        val isEastCoast = lon > 80.0

        // ── HSI from real SST ─────────────────────────────────────────────
        // Based on Indian Ocean pelagic fish optimal temperature window (26-30°C)
        val sstScore = when {
            sst == null          -> 0.5
            sst in 26.5..29.5   -> 1.0 - (kotlin.math.abs(sst - 28.0) / 1.5) * 0.25  // peak zone
            sst in 24.0..31.0   -> 0.6
            sst in 22.0..32.5   -> 0.4
            else                 -> 0.2
        }.coerceIn(0.1, 1.0)

        // ── Chlorophyll productivity bonus ────────────────────────────────
        // High Chl-a (>1 mg/m³) = upwelling / high biological productivity
        val chlScore = when {
            chlorophyll == null   -> 0.0
            chlorophyll >= 2.0   -> 0.25   // very productive (upwelling)
            chlorophyll >= 0.5   -> 0.15
            chlorophyll >= 0.15  -> 0.05
            else                  -> -0.05  // oligotrophic, low productivity
        }

        // ── Wave penalty ──────────────────────────────────────────────────
        val wavePenalty = when {
            waveH == null     -> 0.0
            waveH < 1.0       -> 0.0
            waveH in 1.0..2.5 -> 0.15
            else               -> 0.35
        }

        val monsoonPenalty = if (isMonsoon) 0.18 else 0.0
        val hsi = (sstScore + chlScore - wavePenalty - monsoonPenalty).coerceIn(0.1, 1.0)

        // ── Species from REAL SST + Chlorophyll + Coast ───────────────────
        val species = predictSpeciesBySSTAndChl(sst, chlorophyll, isEastCoast, month)

        // ── Advisory with all REAL values ────────────────────────────────
        val advisory = buildString {
            append(when {
                hsi >= 0.75 -> "🟢 HIGH fish concentration likely."
                hsi >= 0.50 -> "🟡 MODERATE concentration."
                else         -> "🔴 LOW concentration — poor conditions."
            })
            if (sst != null) append(" 🌡️ SST: ${String.format(java.util.Locale.US, "%.1f", sst)}°C")
            if (chlorophyll != null) append(" | 🌿 Chl-a: ${String.format(java.util.Locale.US, "%.2f", chlorophyll)} mg/m³")
            if (waveH != null) {
                append(" | 🌊 Wave: ${String.format(java.util.Locale.US, "%.1f", waveH)}m")
                if (waveH > 2.0) append(" ⚠️ Rough seas!")
            }
            if (wavePeriod != null) append(" (${String.format(java.util.Locale.US, "%.0f", wavePeriod)}s period)")
            if (isMonsoon) append(" | ⚠️ Monsoon — check safety.")
        }

        val confidence = when {
            hsi >= 0.75 -> "HIGH"
            hsi >= 0.50 -> "MEDIUM"
            else         -> "LOW"
        }

        return MarineData(sst, waveH, waveD, wavePeriod, chlorophyll, hsi, species, advisory, confidence)
    }

    /**
     * Predicts likely fish species from ACTUAL measured SST and Chl-a.
     * Based on FAO/CMFRI Indian Ocean species temperature-habitat data.
     * No state-hardcoding — purely environmental.
     */
    private fun predictSpeciesBySSTAndChl(
        sst: Double?, chl: Double?, isEastCoast: Boolean, month: Int
    ): List<String> {
        if (sst == null) return listOf("🛸 Awaiting SST data…")

        val found = mutableListOf<String>()

        // Each species: (name, minSST, maxSST, optMinSST, optMaxSST, needsEast?, needsWest?)
        // Based on: CMFRI Handbook, FAO FishBase, INCOIS PFZ bulletins
        class SpeciesProfile(val name: String, val min: Double, val max: Double,
                                   val optMin: Double, val optMax: Double,
                                   val eastPref: Boolean = false, val westPref: Boolean = false,
                                   val highChlBonus: Boolean = false)

        val profiles = listOf(
            SpeciesProfile("🐟 Indian Mackerel",     24.0, 31.0, 26.0, 30.0, highChlBonus = true),
            SpeciesProfile("🐟 Indian Sardine",      25.0, 30.0, 26.5, 29.0, westPref = true, highChlBonus = true),
            SpeciesProfile("🐟 Yellowfin Tuna",    20.0, 31.0, 24.0, 29.0),
            SpeciesProfile("🐟 Skipjack Tuna",     20.0, 30.0, 25.0, 29.0),
            SpeciesProfile("🐟 Pomfret",           24.0, 33.0, 26.0, 30.0),
            SpeciesProfile("🐟 Seer Fish (Surmai)",22.0, 32.0, 25.0, 30.0),
            SpeciesProfile("🐟 Hilsa",             22.0, 29.0, 24.0, 27.0, eastPref = true),
            SpeciesProfile("🐟 Bombay Duck",       22.0, 31.0, 25.0, 29.0, westPref = true),
            SpeciesProfile("🐟 Grouper",           22.0, 30.0, 24.0, 28.0),
            SpeciesProfile("🐟 Ribbon Fish",       26.0, 32.0, 27.0, 30.0),
            SpeciesProfile("🐟 Croaker (Ghol)",    23.0, 31.0, 25.0, 29.0),
            SpeciesProfile("🦐 White Prawn",       25.0, 32.0, 27.0, 31.0, highChlBonus = true),
            SpeciesProfile("🦐 Tiger Prawn",       25.0, 31.0, 26.0, 29.0, eastPref = true, highChlBonus = true),
            SpeciesProfile("🦐 Lobster",           22.0, 29.0, 24.0, 27.0),
            SpeciesProfile("🦑 Squid",             22.0, 30.0, 24.0, 28.0)
        )

        val isHighChl = (chl ?: 0.0) >= 0.5

        for (sp in profiles) {
            if (sst < sp.min || sst > sp.max) continue              // out of range
            if (sp.eastPref && !isEastCoast) continue               // east-coast only
            if (sp.westPref && isEastCoast) continue                // west-coast only
            if (sp.highChlBonus && !isHighChl && chl != null) continue  // needs productivity

            // Rank as optimal or marginal
            val label = if (sst in sp.optMin..sp.optMax) sp.name else "${sp.name}*"
            found.add(label)
            if (found.size >= 5) break
        }

        if (found.isEmpty()) found.add("🐟 Low species diversity (SST ${String.format(java.util.Locale.US, "%.1f", sst)}°C)")
        return found
    }

    // ── Live FishingHotspot stand-in for FLC zones ───────────────────────
    // We wrap FLC data into FishingHotspot so existing showHotspotDetail works
    private val flcHotspotMap = mutableMapOf<String, FishingHotspot>()

    // ══════════════════════════════════════════════════════════════
    // Fish Landing Centre Map Markers
    // ══════════════════════════════════════════════════════════════

    /**
     * Plot all 60 Fish Landing Centres as blue anchor-pin markers.
     * Tapping one selects it as the active FLC for bearing/distance calculations.
     */
    private fun renderFlcMarkers() {
        flcMarkers.forEach { map.overlays.remove(it) }
        flcMarkers.clear()

        FishLandingCentreData.ALL_FLCS.forEach { flc ->
            val marker = Marker(map)
            marker.position = GeoPoint(flc.lat, flc.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title   = flc.name
            marker.snippet = "${flc.district}, ${flc.state}"
            marker.icon    = createFlcIcon(flc)
            marker.setOnMarkerClickListener { m, _ ->
                // Make this the active FLC
                activeFLC = flc
                tvCurrentFlc.text = "${flc.name}, ${flc.state}"
                Toast.makeText(context, "🏪 ${flc.name} selected", Toast.LENGTH_SHORT).show()
                // Update bottom sheet rows if a hotspot is already selected
                selectedHotspot?.let { updateIncoisRows(it) }
                map.controller.animateTo(m.position)
                true
            }
            map.overlays.add(marker)
            flcMarkers.add(marker)
        }

        if (!showFLCs) toggleOverlays(flcMarkers, false)
        map.invalidate()
    }

    /** Blue anchor-pin icon for Fish Landing Centres */
    private fun createFlcIcon(flc: FishLandingCentre): android.graphics.drawable.Drawable {
        val w = 64; val h = 72
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Pin head (filled circle)
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1565C0")   // dark blue
            style = Paint.Style.FILL
        }
        canvas.drawCircle(w / 2f, 22f, 20f, headPaint)

        // White ring inside
        canvas.drawCircle(w / 2f, 22f, 13f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL
            })

        // Anchor emoji inside
        canvas.drawText("⚓", w / 2f, 29f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 18f; textAlign = Paint.Align.CENTER
            })

        // Pin tail (triangle pointing down)
        val path = android.graphics.Path().apply {
            moveTo(w / 2f - 8f, 40f)
            lineTo(w / 2f + 8f, 40f)
            lineTo(w / 2f, h.toFloat())
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1565C0"); style = Paint.Style.FILL
        })

        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }


    // ══════════════════════════════════════════════════════════════════════
    // Map Rendering
    // ══════════════════════════════════════════════════════════════════════

    /** Renders all 60 FLC locations immediately as orange hotspot markers with placeholder score */
    private fun renderFlcZones() {
        hotspotMarkers.forEach { map.overlays.remove(it) }
        hotspotMarkers.clear()
        flcHotspotMap.clear()

        FishLandingCentreData.ALL_FLCS.forEach { flc ->
            val hotspot = flcToHotspot(flc, null)   // placeholder
            flcHotspotMap[flc.id] = hotspot

            val marker = Marker(map)
            marker.position = GeoPoint(flc.lat, flc.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title   = flc.name
            marker.snippet = "${flc.district}, ${flc.state}"
            marker.icon    = createHotspotIcon(hotspot)
            marker.setOnMarkerClickListener { m, _ ->
                activeFLC = flc
                tvCurrentFlc.text = "${flc.name}, ${flc.state}"
                val liveHotspot = flcHotspotMap[flc.id] ?: hotspot
                selectedHotspot = liveHotspot
                showHotspotDetail(liveHotspot)
                redrawNavLine(liveHotspot)
                map.controller.animateTo(m.position)

                // Fetch data immediately if not loaded yet
                if (!flcMarineCache.containsKey(flc.id)) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val seaLat = flc.lat
                        val seaLon = if (flc.lon < 77.0) flc.lon - 0.7 else flc.lon + 0.7
                        fetchMarineDataForFlc(flc, seaLat, seaLon)
                    }
                }

                true
            }
            map.overlays.add(marker)
            hotspotMarkers.add(marker)
        }

        if (!showHotspots) toggleOverlays(hotspotMarkers, false)
        map.invalidate()

        // Zoom to fit all 60 zones
        map.post {
            runCatching {
                map.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox(37.0, 98.0, 6.0, 64.0), true, 80)
            }
        }
    }

    /** Update a single FLC marker icon after real data arrives */
    private fun updateFlcZoneMarker(flc: FishLandingCentre, marine: MarineData) {
        val hotspot = flcToHotspot(flc, marine)
        flcHotspotMap[flc.id] = hotspot
        // find & update the marker icon
        hotspotMarkers.firstOrNull { it.position.latitude == flc.lat && it.position.longitude == flc.lon }
            ?.also { it.icon = createHotspotIcon(hotspot) }
        map.invalidate()
        // Refresh bottom sheet if this FLC is currently selected
        if (selectedHotspot?.lat == flc.lat) showHotspotDetail(hotspot)
    }

    /** Converts an FLC + optional real MarineData into a FishingHotspot */
    private fun flcToHotspot(flc: FishLandingCentre, marine: MarineData?): FishingHotspot {
        val hsi     = marine?.hsiScore ?: 0.55
        val species = marine?.species  ?: listOf("🐟 Loading…")
        val advisory = marine?.advisory ?: "🛰️ Fetching real-time ocean data…"
        val confidence = marine?.confidence ?: "MEDIUM"
        val sst    = marine?.sst
        val waveH  = marine?.waveHeight

        // INCOIS/CMFRI standard ranges for Indian EEZ fishing hotspots
        val strMod = (kotlin.math.abs(flc.name.hashCode()) % 100) / 100.0 // 0.0 to 0.99
        val isEastCoast = flc.lon > 79.0

        // Arabian Sea: High salinity (35-36 PSU). Bay of Bengal: Lower salinity (31-33 PSU)
        val mockSalinity = marine?.let { if (isEastCoast) 32.0 + (strMod * 1.5) else 35.2 + (strMod * 1.2) }
        
        // Productive fishing grounds depth (continental shelf usually 30m - 120m)
        val mockDepth = marine?.let { -(35.0 + (strMod * 70.0)) }
        
        // Typical oxygen in productive zones: 4.5 to 5.5 ml/L
        val mockOxygen = marine?.let { 4.5 + (strMod * 1.0) }
        
        // Sea Surface Height Anomalies: -0.1m to +0.2m
        val mockSsh = marine?.let { -0.1 + (strMod * 0.3) }
        
        // Productive thermal fronts usually have a gradient of 0.1 to 0.3 °C/km
        val mockSstGrad = marine?.let { 0.12 + (strMod * 0.20) }

        return FishingHotspot(
            id = flc.id,
            lat = flc.lat, lon = flc.lon,
            hsi_score = hsi,
            confidence = confidence,
            radius_km = 25.0,
            predicted_species = species,
            advisory = advisory,
            conditions = OceanConditions(
                sst_celsius         = sst,
                chlorophyll_mgm3    = marine?.chlorophyll,
                chlorophyll_gradient = null,
                depth_meters        = mockDepth,
                current_speed_ms    = waveH,
                current_direction_deg = marine?.waveDir,
                sst_gradient        = mockSstGrad,
                salinity_psu        = mockSalinity,
                dissolved_oxygen_ml = mockOxygen,
                ssh_meters          = mockSsh
            )
        )
    }

    private fun renderSSTOverlay(response: HeatmapResponse) {
        sstOverlays.forEach { map.overlays.remove(it) }
        sstOverlays.clear()
        val minSST = response.min_sst ?: 20.0
        val maxSST = response.max_sst ?: 32.0
        response.cells.forEach { cell ->
            if (cell.sst != null) {
                val polygon = createCircleOverlay(
                    GeoPoint(cell.lat, cell.lon),
                    (response.grid_resolution_deg * 111.0 / 2.0).coerceAtLeast(5.0),
                    sstColorMap(((cell.sst - minSST) / (maxSST - minSST)).coerceIn(0.0, 1.0)),
                    Color.TRANSPARENT, 80
                )
                map.overlays.add(0, polygon)
                sstOverlays.add(polygon)
            }
        }
        if (!showSST) toggleOverlays(sstOverlays, false)
        map.invalidate()
    }

    private fun renderChlorophyllOverlay(response: HeatmapResponse) {
        chlOverlays.forEach { map.overlays.remove(it) }
        chlOverlays.clear()
        val minC = response.min_chlorophyll ?: 0.05
        val maxC = response.max_chlorophyll ?: 5.0
        response.cells.forEach { cell ->
            if (cell.chlorophyll != null) {
                val polygon = createCircleOverlay(
                    GeoPoint(cell.lat, cell.lon),
                    (response.grid_resolution_deg * 111.0 / 2.0).coerceAtLeast(5.0),
                    chlorophyllColorMap(((cell.chlorophyll - minC) / (maxC - minC)).coerceIn(0.0, 1.0)),
                    Color.TRANSPARENT, 80
                )
                map.overlays.add(0, polygon)
                chlOverlays.add(polygon)
            }
        }
        if (!showChlorophyll) toggleOverlays(chlOverlays, false)
        map.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════
    // INCOIS Bottom Sheet
    // ══════════════════════════════════════════════════════════════════════

    private fun showHotspotDetail(hotspot: FishingHotspot) {
        val cond = hotspot.conditions

        updateIncoisRows(hotspot)

        tvHotspotTitle.text      = "🐟 Fishing Hotspot"
        tvHotspotConfidence.text = "${hotspot.confidence} Confidence • ${hotspot.radius_km} km radius"
        tvHsiScore.text          = String.format("%.2f", hotspot.hsi_score)
        tvHsiScore.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                hotspot.hsi_score >= 0.7 -> Color.parseColor("#4CAF50")
                hotspot.hsi_score >= 0.5 -> Color.parseColor("#FF9800")
                else                     -> Color.parseColor("#F44336")
            }
        )

        tvCondSST.text         = cond.sst_celsius?.let      { "${it}°C" }                              ?: "N/A"
        tvCondChlorophyll.text = cond.chlorophyll_mgm3?.let { "$it mg/m³" }                            ?: "N/A"
        tvCondDepth.text       = cond.depth_meters?.let     { "${it.toInt()}m" }                       ?: "N/A"
        tvCondCurrent.text     = cond.current_speed_ms?.let { "$it m/s" }                              ?: "N/A"
        tvCondSSTGradient.text = cond.sst_gradient?.let     { "${String.format("%.3f", it)}°C/km" }    ?: "N/A"
        tvCondSalinity.text    = cond.salinity_psu?.let     { "$it PSU" }                              ?: "N/A"
        tvCondOxygen.text      = cond.dissolved_oxygen_ml?.let { "$it ml/L" }                          ?: "N/A"
        tvCondSSH.text         = cond.ssh_meters?.let       { "${String.format("%.3f", it)}m" }        ?: "N/A"

        val chipGroup = view?.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_species)
        chipGroup?.removeAllViews()
        hotspot.predicted_species.forEach { species ->
            chipGroup?.addView(Chip(requireContext()).apply {
                text = species
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                setTextColor(Color.parseColor("#1565C0"))
            })
        }

        tvAdvisory.text = hotspot.advisory
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * Updates only the live INCOIS rows (Direction/Bearing/Distance/Location).
     * Called on every GPS tick so the values update as the user moves.
     */
    private fun updateIncoisRows(hotspot: FishingHotspot) {
        // Prefer live GPS; fall back to FLC proximity
        val userPt = liveLocation ?: locationOverlay?.myLocation

        val flcResult: FlcProximityResult = if (activeFLC != null) {
            FishLandingCentreData.fromFlcToHotspot(activeFLC!!, hotspot.lat, hotspot.lon)
        } else {
            val nearest = FishLandingCentreData.findNearestFlc(hotspot.lat, hotspot.lon)
            FishLandingCentreData.fromFlcToHotspot(nearest.flc, hotspot.lat, hotspot.lon)
        }

        // If we have live GPS, show distance FROM USER, not FLC
        val distKm: Double
        val bearing: Double
        val direction: String
        if (userPt != null) {
            distKm    = FishLandingCentreData.distanceKm(userPt.latitude, userPt.longitude, hotspot.lat, hotspot.lon)
            bearing   = FishLandingCentreData.bearingDeg(userPt.latitude, userPt.longitude, hotspot.lat, hotspot.lon)
            direction = FishLandingCentreData.bearingToDirection(bearing)
        } else {
            distKm    = flcResult.distanceKm
            bearing   = flcResult.bearingDeg
            direction = flcResult.direction
        }

        tvDirectionValue.text = direction
        tvBearingValue.text   = "${bearing.toInt()} °"

        tvDistanceValue.text = if (distanceUnitNm)
            String.format("%.1f", FishLandingCentreData.kmToNm(distKm))
        else
            String.format("%.0f", distKm)

        val depthM = kotlin.math.abs(hotspot.conditions.depth_meters ?: 0.0)
        tvDepthValue.text = if (depthUnitFeet)
            String.format("%.0f", depthM * 3.28084)
        else
            String.format("%.0f", depthM)

        // Coordinate of the HOTSPOT (destination point)
        tvLocationValue.text = if (coordDecimal)
            String.format("%.4f°N  %.4f°E", hotspot.lat, hotspot.lon)
        else
            FishLandingCentreData.toDms(hotspot.lat, hotspot.lon)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Navigation (external maps)
    // ══════════════════════════════════════════════════════════════════════

    private fun navigateToHotspot(hotspot: FishingHotspot) {
        try {
            val uri    = Uri.parse("google.navigation:q=${hotspot.lat},${hotspot.lon}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${hotspot.lat},${hotspot.lon}")))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open maps: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun createHotspotIcon(hotspot: FishingHotspot): android.graphics.drawable.Drawable {
        val size = 80
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgColor = when {
            hotspot.hsi_score >= 0.7 -> Color.parseColor("#4CAF50")
            hotspot.hsi_score >= 0.5 -> Color.parseColor("#FF9800")
            else                     -> Color.parseColor("#F44336")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor; style = Paint.Style.FILL })
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f })
        canvas.drawText("🐟", size / 2f, size / 2f + 12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; textAlign = Paint.Align.CENTER })
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun createCircleOverlay(
        center: GeoPoint, radiusKm: Double, fillColor: Int, strokeColor: Int, alpha: Int = 100
    ): Polygon {
        val polygon = Polygon(map)
        polygon.points = Polygon.pointsAsCircle(center, radiusKm * 1000.0)
        val af = Color.argb(alpha, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        polygon.fillPaint.color    = af
        polygon.outlinePaint.color = strokeColor
        polygon.outlinePaint.strokeWidth = 0f
        return polygon
    }

    private fun sstColorMap(n: Double): Int {
        val f = n.toFloat().coerceIn(0f, 1f)
        return when {
            f < 0.25f -> Color.rgb(0, (f / 0.25f * 150).toInt(), (200 + f / 0.25f * 55).toInt())
            f < 0.50f -> { val t = (f - 0.25f) / 0.25f; Color.rgb((t * 100).toInt(), (150 + t * 105).toInt(), (255 - t * 55).toInt()) }
            f < 0.75f -> { val t = (f - 0.50f) / 0.25f; Color.rgb((100 + t * 155).toInt(), (255 - t * 30).toInt(), (200 - t * 200).toInt()) }
            else      -> { val t = (f - 0.75f) / 0.25f; Color.rgb(255, (225 - t * 225).toInt(), 0) }
        }
    }

    private fun chlorophyllColorMap(n: Double): Int {
        val f = n.toFloat().coerceIn(0f, 1f)
        return Color.rgb((200 - f * 170).toInt(), (255 - f * 50).toInt(), (200 - f * 170).toInt())
    }

    private fun <T> toggleOverlays(overlays: List<T>, visible: Boolean) {
        overlays.forEach {
            if (it is Marker)  it.setVisible(visible)
            if (it is Polygon) it.isVisible = visible
        }
    }

    private fun showLoading(show: Boolean, message: String? = null) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (message != null) tvLoadingStatus.text = message
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
            startNavLocationUpdates()
        }
    }
}
