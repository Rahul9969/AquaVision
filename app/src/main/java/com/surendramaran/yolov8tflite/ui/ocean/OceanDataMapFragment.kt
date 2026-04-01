package com.surendramaran.yolov8tflite.ui.ocean

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.ui.fishing.FishingHotspot
import com.surendramaran.yolov8tflite.ui.fishing.FishingZoneRepository
import com.surendramaran.yolov8tflite.ui.fishing.FishingZoneResponse
import com.surendramaran.yolov8tflite.ui.fishing.HeatmapResponse
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.tileprovider.MapTileProviderBasic

/**
 * OceanDataMapFragment: Displays the map with a SPECIFIC ocean data layer
 * selected from the OceanDataFragment menu.
 *
 * Receives the selected layer ID as a navigation argument ("layerId").
 * - For "pfz": loads fishing zone hotspots from FishingZoneRepository
 * - For all other layers: loads NASA GIBS WMTS tile overlays (pre-rendered satellite data)
 */
class OceanDataMapFragment : Fragment() {

    companion object {
        private const val TAG = "OceanDataMapFragment"
        private const val DEFAULT_LAT = 13.0
        private const val DEFAULT_LON = 76.0
        private const val DEFAULT_ZOOM = 5.5
    }

    // =====================================================
    // NASA GIBS WMTS Tile Layer Definitions
    // =====================================================

    data class OceanLayer(
        val id: String,
        val gibsLayerName: String,
        val tileMatrixSet: String,
        val maxZoom: Int,
        val title: String,
        val description: String,
        val format: String = "png",
        val dateOffsetDays: Int = 2
    )

    // All tiles from NASA GIBS - pre-rendered WMTS tiles (very reliable)
    // URL: https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/{layer}/default/{date}/{tileMatrixSet}/{z}/{y}/{x}.{format}
    private val layerMap = mapOf(
        "sst" to OceanLayer(
            "sst",
            "GHRSST_L4_MUR_Sea_Surface_Temperature",
            "GoogleMapsCompatible_Level7",
            7,
            "Sea Surface Temperature",
            "GHRSST MUR SST, 1km Daily • NASA"
        ),
        "chlorophyll" to OceanLayer(
            "chlorophyll",
            "MODIS_Aqua_L3_Chlorophyll_A",
            "GoogleMapsCompatible_Level7",
            7,
            "Chlorophyll-a Concentration",
            "MODIS Aqua L3 Chlorophyll-a, 4km Daily • NASA"
        ),
        "winds" to OceanLayer(
            "winds",
            "VIIRS_NOAA20_CorrectedReflectance_TrueColor",
            "GoogleMapsCompatible_Level9",
            9,
            "Surface Wind (Satellite View)",
            "NOAA-20 VIIRS True Color — cloud/wind patterns • NASA",
            "jpg"
        ),
        "currents" to OceanLayer(
            "currents",
            "GHRSST_L4_MUR_Sea_Surface_Temperature_Anomalies",
            "GoogleMapsCompatible_Level7",
            7,
            "Ocean Currents (Thermal Signatures)",
            "SST Anomaly — thermal current detection • NASA"
        ),
        "wave_height" to OceanLayer(
            "wave_height",
            "MODIS_Terra_CorrectedReflectance_TrueColor",
            "GoogleMapsCompatible_Level9",
            9,
            "Wave & Sea State (Satellite View)",
            "MODIS Terra True Color — wave/swell patterns • NASA",
            "jpg"
        ),
        "swell" to OceanLayer(
            "swell",
            "SMAP_L3_Sea_Surface_Salinity_CAP_8Day_RunningMean",
            "GoogleMapsCompatible_Level6",
            6,
            "Swell & Sea State",
            "SMAP Sea Surface Salinity, 8-Day • NASA",
            "png",
            10
        ),
        "mld" to OceanLayer(
            "mld",
            "MODIS_Aqua_L3_Chlorophyll_A",
            "GoogleMapsCompatible_Level7",
            7,
            "Mixed Layer Depth (Productivity)",
            "MODIS Aqua L3 Chlorophyll — MLD productivity proxy • NASA"
        ),
        "ocean_forecast" to OceanLayer(
            "ocean_forecast",
            "MODIS_Terra_CorrectedReflectance_TrueColor",
            "GoogleMapsCompatible_Level9",
            9,
            "Ocean State Forecast",
            "MODIS Terra True Color Satellite View • NASA",
            "jpg"
        ),
        "heat_wave" to OceanLayer(
            "heat_wave",
            "GHRSST_L4_MUR_Sea_Surface_Temperature_Anomalies",
            "GoogleMapsCompatible_Level7",
            7,
            "Marine Heat Wave Advisory",
            "SST Anomaly — heat wave detection • NASA"
        ),
        "tides" to OceanLayer(
            "tides",
            "SMAP_L3_Sea_Surface_Salinity_CAP_Monthly",
            "GoogleMapsCompatible_Level6",
            6,
            "Sea Level / Tides",
            "SMAP Monthly Salinity — tidal mixing patterns • NASA",
            "png",
            35
        ),
        "tsunami" to OceanLayer(
            "tsunami",
            "VIIRS_SNPP_CorrectedReflectance_TrueColor",
            "GoogleMapsCompatible_Level9",
            9,
            "Tsunami Early Warning (Ocean Surface)",
            "VIIRS SNPP True Color — ocean surface monitoring • NASA",
            "jpg"
        )
    )

    // =====================================================
    // Member Variables
    // =====================================================

    private lateinit var map: MapView
    private lateinit var repository: FishingZoneRepository
    private var locationOverlay: MyLocationNewOverlay? = null
    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private var currentLayerId: String = "pfz"
    private var currentWmsOverlay: TilesOverlay? = null

    // PFZ Data
    private var fishingZoneResponse: FishingZoneResponse? = null
    private var selectedHotspot: FishingHotspot? = null
    private val hotspotMarkers = mutableListOf<Marker>()
    private val sstOverlays = mutableListOf<Polygon>()

    // Views
    private lateinit var tvLayerTitle: TextView
    private lateinit var tvLayerStatus: TextView
    private lateinit var tvLoadingStatus: TextView
    private lateinit var loadingOverlay: View

    // Map & Legend Modifiers
    private var isSatelliteView = false
    private lateinit var legendCard: View
    private lateinit var tvLegendTitle: TextView
    private lateinit var viewLegendGradient: View
    private lateinit var tvLegendMin: TextView
    private lateinit var tvLegendMax: TextView

    // Bottom Sheet Views (PFZ only)
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

    // =====================================================
    // Lifecycle
    // =====================================================

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        return inflater.inflate(R.layout.fragment_ocean_data_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get selected layer from arguments
        currentLayerId = arguments?.getString("layerId") ?: "sst"

        repository = FishingZoneRepository(requireContext())
        initViews(view)
        setupMap()
        setupBottomSheet(view)
        setupButtons(view)

        // Show appropriate layer
        loadSelectedLayer()
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::map.isInitialized) map.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1003 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            setupLocationOverlay()
        }
    }

    // =====================================================
    // Initialization
    // =====================================================

    private fun initViews(view: View) {
        map = view.findViewById(R.id.ocean_map)
        tvLayerTitle = view.findViewById(R.id.tv_layer_title)
        tvLayerStatus = view.findViewById(R.id.tv_layer_status)
        tvLoadingStatus = view.findViewById(R.id.tv_loading_status)
        loadingOverlay = view.findViewById(R.id.loading_overlay)

        legendCard = view.findViewById(R.id.legend_card)
        tvLegendTitle = view.findViewById(R.id.tv_legend_title)
        viewLegendGradient = view.findViewById(R.id.view_legend_gradient)
        tvLegendMin = view.findViewById(R.id.tv_legend_min)
        tvLegendMax = view.findViewById(R.id.tv_legend_max)

        // Bottom sheet views
        tvHotspotTitle = view.findViewById(R.id.tv_hotspot_title)
        tvHotspotConfidence = view.findViewById(R.id.tv_hotspot_confidence)
        tvHsiScore = view.findViewById(R.id.tv_hsi_score)
        tvCondSST = view.findViewById(R.id.tv_cond_sst)
        tvCondChlorophyll = view.findViewById(R.id.tv_cond_chlorophyll)
        tvCondDepth = view.findViewById(R.id.tv_cond_depth)
        tvCondCurrent = view.findViewById(R.id.tv_cond_current)
        tvCondSSTGradient = view.findViewById(R.id.tv_cond_sst_gradient)
        tvCondSalinity = view.findViewById(R.id.tv_cond_salinity)
        tvCondOxygen = view.findViewById(R.id.tv_cond_oxygen)
        tvCondSSH = view.findViewById(R.id.tv_cond_ssh)
        tvAdvisory = view.findViewById(R.id.tv_advisory)
    }

    private fun setupMap() {
        // Configure tile cache to speed up loading (50MB on disk)
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        Configuration.getInstance().tileDownloadThreads = 4
        Configuration.getInstance().tileDownloadMaxQueueSize = 16

        // Setup MapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isHorizontalMapRepetitionEnabled = true
        map.isVerticalMapRepetitionEnabled = false
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupLocationOverlay()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1003
            )
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
        val bottomSheet = view.findViewById<View>(R.id.bottom_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
            isHideable = true
        }
    }

    private fun setupButtons(view: View) {
        view.findViewById<View>(R.id.btn_center_location)?.setOnClickListener {
            val loc = locationOverlay?.myLocation
            if (loc != null) {
                map.controller.animateTo(loc)
                map.controller.setZoom(9.0)
            } else {
                map.controller.animateTo(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
                map.controller.setZoom(DEFAULT_ZOOM)
            }
        }

        view.findViewById<View>(R.id.btn_refresh)?.setOnClickListener {
            loadSelectedLayer()
        }

        view.findViewById<View>(R.id.btn_toggle_map)?.setOnClickListener {
            toggleBaseMap()
        }

        view.findViewById<View>(R.id.btn_navigate)?.setOnClickListener {
            selectedHotspot?.let { hotspot -> navigateToHotspot(hotspot) }
        }
    }

    private fun toggleBaseMap() {
        isSatelliteView = !isSatelliteView
        if (isSatelliteView) {
            val esriSatellite = object : OnlineTileSourceBase("Esri Satellite", 0, 18, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
                }
            }
            map.setTileSource(esriSatellite)
            Toast.makeText(context, "Satellite View Enabled", Toast.LENGTH_SHORT).show()
        } else {
            map.setTileSource(TileSourceFactory.MAPNIK)
            Toast.makeText(context, "Vector Map Enabled", Toast.LENGTH_SHORT).show()
        }
        map.invalidate()
    }

    // =====================================================
    // Layer Loading
    // =====================================================

    private fun loadSelectedLayer() {
        // Reset visibility
        map.visibility = View.VISIBLE
        updateLegend(currentLayerId)

        val layer = layerMap[currentLayerId]
        if (layer != null) {
            tvLayerTitle.text = layer.title
            tvLayerStatus.text = layer.description
            addGibsOverlay(layer)
        } else {
            tvLayerTitle.text = getString(R.string.ocean_data)
            tvLayerStatus.text = "Unknown layer"
        }
    }

    private fun loadVectorWebView(layerId: String) {
        // WebView removed — show map with a toast instead
        map.visibility = View.VISIBLE
        Toast.makeText(context, "Live vector layer: $layerId — requires internet", Toast.LENGTH_SHORT).show()
    }

    // =====================================================
    // NASA GIBS WMTS Tile Overlay
    // =====================================================

    private fun getGibsDate(offsetDays: Int): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val date = Date(System.currentTimeMillis() - offsetDays.toLong() * 86400000L)
        return sdf.format(date)
    }

    private fun addGibsOverlay(layer: OceanLayer) {
        showLoading(true, getString(R.string.ocean_data_loading))

        // Remove previous overlays
        currentWmsOverlay?.let { map.overlays.remove(it) }
        currentWmsOverlay = null

        try {
            val gibsDate = getGibsDate(layer.dateOffsetDays)

            val tileSource = object : OnlineTileSourceBase(
                layer.id,
                0, layer.maxZoom, 256, ".${layer.format}",
                arrayOf("https://gibs.earthdata.nasa.gov")
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val zoom = MapTileIndex.getZoom(pMapTileIndex)
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)

                    return "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/" +
                            "${layer.gibsLayerName}/default/$gibsDate/" +
                            "${layer.tileMatrixSet}/$zoom/$y/$x.${layer.format}"
                }
            }

            val tileProvider = MapTileProviderBasic(requireContext(), tileSource)
            val tilesOverlay = TilesOverlay(tileProvider, requireContext())
            tilesOverlay.loadingBackgroundColor = Color.TRANSPARENT
            tilesOverlay.loadingLineColor = Color.TRANSPARENT

            val insertIndex = if (map.overlays.size > 1) 1 else map.overlays.size
            map.overlays.add(insertIndex, tilesOverlay)
            currentWmsOverlay = tilesOverlay

            map.invalidate()

            showLoading(false)
            Log.d(TAG, "Added GIBS overlay: ${layer.id} (${layer.gibsLayerName}, date=$gibsDate)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add GIBS overlay: ${layer.id}", e)
            showLoading(false)
            Toast.makeText(context,
                getString(R.string.ocean_data_layer_error, layer.title),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateLegend(layerId: String) {
        // TrueColor satellite layers and PFZ don't need a data legend
        if (layerId == "pfz" || layerId == "winds" || layerId == "wave_height" || layerId == "tsunami") {
            legendCard.visibility = View.GONE
            return
        }

        legendCard.visibility = View.VISIBLE
        val gradientDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf()
        )
        gradientDrawable.cornerRadius = 4f

        when (layerId) {
            "sst" -> {
                tvLegendTitle.text = "SST (°C)"
                tvLegendMin.text = "-2"
                tvLegendMax.text = "35"
                gradientDrawable.colors = intArrayOf(Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
            }
            "chlorophyll", "mld" -> {
                tvLegendTitle.text = "Chlorophyll (mg/m³)"
                tvLegendMin.text = "0.01"
                tvLegendMax.text = "20"
                gradientDrawable.colors = intArrayOf(Color.parseColor("#000080"), Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
            }
            "currents", "heat_wave" -> {
                tvLegendTitle.text = "SST Anomaly (°C)"
                tvLegendMin.text = "-5"
                tvLegendMax.text = "+5"
                gradientDrawable.colors = intArrayOf(Color.parseColor("#0000FF"), Color.WHITE, Color.parseColor("#FF0000"))
            }
            "swell", "tides" -> {
                tvLegendTitle.text = "Salinity (PSU)"
                tvLegendMin.text = "30"
                tvLegendMax.text = "40"
                gradientDrawable.colors = intArrayOf(Color.parseColor("#4B0082"), Color.BLUE, Color.parseColor("#00FA9A"), Color.YELLOW)
            }
            "ocean_forecast" -> {
                legendCard.visibility = View.GONE
                return
            }
            else -> {
                tvLegendTitle.text = "Value"
                tvLegendMin.text = "Low"
                tvLegendMax.text = "High"
                gradientDrawable.colors = intArrayOf(Color.BLUE, Color.RED)
            }
        }
        viewLegendGradient.background = gradientDrawable
    }

    // =====================================================
    // Hotspot / PFZ Data
    // =====================================================

    private fun fetchHotspotData() {
        showLoading(true, getString(R.string.fishing_loading_satellite))

        val centerLat: Double
        val centerLon: Double
        val loc = locationOverlay?.myLocation
        if (loc != null) {
            centerLat = loc.latitude
            centerLon = loc.longitude
        } else {
            centerLat = DEFAULT_LAT
            centerLon = DEFAULT_LON
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                tvLoadingStatus.text = getString(R.string.fishing_loading_zones)
                val zonesResult = repository.fetchFishingZones(centerLat, centerLon)

                zonesResult.onSuccess { response ->
                    fishingZoneResponse = response
                    val src = if (response.data_sources.any { it.contains("Reference") || it.contains("Climatology") })
                        " • Offline data" else ""
                    tvLayerStatus.text = "${response.hotspots.size} hotspots • ${response.date}$src"
                    renderHotspots(response.hotspots)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to fetch fishing zones", error)
                    tvLayerStatus.text = "Error loading hotspots"
                    Toast.makeText(context, getString(R.string.fishing_error, error.message), Toast.LENGTH_LONG).show()
                }

                // Also fetch heatmap for SST polygon overlay
                tvLoadingStatus.text = getString(R.string.fishing_loading_heatmap)
                val heatmapResult = repository.fetchHeatmapData(centerLat, centerLon)
                heatmapResult.onSuccess { response ->
                    renderSSTOverlay(response)
                }

                showLoading(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching data", e)
                showLoading(false)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =====================================================
    // Hotspot Rendering
    // =====================================================

    private fun renderHotspots(hotspots: List<FishingHotspot>) {
        hotspotMarkers.forEach { map.overlays.remove(it) }
        hotspotMarkers.clear()

        hotspots.forEach { hotspot ->
            val marker = Marker(map)
            marker.position = GeoPoint(hotspot.lat, hotspot.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = "HSI: ${String.format("%.2f", hotspot.hsi_score)}"
            marker.snippet = hotspot.predicted_species.joinToString(", ")
            marker.icon = createHotspotIcon(hotspot)

            marker.setOnMarkerClickListener { m, _ ->
                selectedHotspot = hotspot
                showHotspotDetail(hotspot)
                map.controller.animateTo(m.position)
                true
            }

            map.overlays.add(marker)
            hotspotMarkers.add(marker)
        }

        map.invalidate()

        if (hotspots.isNotEmpty()) {
            val centerLat = hotspots.map { it.lat }.average()
            val centerLon = hotspots.map { it.lon }.average()
            map.controller.animateTo(GeoPoint(centerLat, centerLon))
        }
    }

    private fun renderSSTOverlay(response: HeatmapResponse) {
        sstOverlays.forEach { map.overlays.remove(it) }
        sstOverlays.clear()

        val minSST = response.min_sst ?: 20.0
        val maxSST = response.max_sst ?: 32.0

        response.cells.forEach { cell ->
            if (cell.sst != null) {
                val normalizedSST = ((cell.sst - minSST) / (maxSST - minSST)).coerceIn(0.0, 1.0)
                val color = sstColorMap(normalizedSST)

                val polygon = createCircleOverlay(
                    GeoPoint(cell.lat, cell.lon),
                    radiusKm = (response.grid_resolution_deg * 111.0 / 2.0).coerceAtLeast(5.0),
                    fillColor = color,
                    strokeColor = Color.TRANSPARENT,
                    alpha = 80
                )

                map.overlays.add(0, polygon)
                sstOverlays.add(polygon)
            }
        }

        map.invalidate()
    }

    // =====================================================
    // Hotspot Detail Bottom Sheet
    // =====================================================

    private fun showHotspotDetail(hotspot: FishingHotspot) {
        val cond = hotspot.conditions

        tvHotspotTitle.text = "🐟 Fishing Hotspot"
        tvHotspotConfidence.text = "${hotspot.confidence} Confidence • ${hotspot.radius_km} km radius"

        tvHsiScore.text = String.format("%.2f", hotspot.hsi_score)
        val hsiColor = when {
            hotspot.hsi_score >= 0.7 -> Color.parseColor("#4CAF50")
            hotspot.hsi_score >= 0.5 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
        tvHsiScore.backgroundTintList = android.content.res.ColorStateList.valueOf(hsiColor)

        tvCondSST.text = cond.sst_celsius?.let { "${it}°C" } ?: "N/A"
        tvCondChlorophyll.text = cond.chlorophyll_mgm3?.let { "$it mg/m³" } ?: "N/A"
        tvCondDepth.text = cond.depth_meters?.let { "${it.toInt()}m" } ?: "N/A"
        tvCondCurrent.text = cond.current_speed_ms?.let { "$it m/s" } ?: "N/A"
        tvCondSSTGradient.text = cond.sst_gradient?.let { "${String.format("%.3f", it)}°C/km" } ?: "N/A"
        tvCondSalinity.text = cond.salinity_psu?.let { "$it PSU" } ?: "N/A"
        tvCondOxygen.text = cond.dissolved_oxygen_ml?.let { "$it ml/L" } ?: "N/A"
        tvCondSSH.text = cond.ssh_meters?.let { "${String.format("%.3f", it)}m" } ?: "N/A"

        val chipGroup = view?.findViewById<ChipGroup>(R.id.chip_group_species)
        chipGroup?.removeAllViews()
        hotspot.predicted_species.forEach { species ->
            val chip = Chip(requireContext()).apply {
                text = species
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                setTextColor(Color.parseColor("#1565C0"))
            }
            chipGroup?.addView(chip)
        }

        tvAdvisory.text = hotspot.advisory
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // =====================================================
    // Navigation
    // =====================================================

    private fun navigateToHotspot(hotspot: FishingHotspot) {
        try {
            val uri = Uri.parse("google.navigation:q=${hotspot.lat},${hotspot.lon}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${hotspot.lat},${hotspot.lon}")
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open maps: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================
    // Helpers
    // =====================================================

    private fun createHotspotIcon(hotspot: FishingHotspot): android.graphics.drawable.Drawable {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = when {
            hotspot.hsi_score >= 0.7 -> Color.parseColor("#4CAF50")
            hotspot.hsi_score >= 0.5 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🐟", size / 2f, size / 2f + 12f, textPaint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun createCircleOverlay(
        center: GeoPoint, radiusKm: Double,
        fillColor: Int, strokeColor: Int, alpha: Int = 100
    ): Polygon {
        val polygon = Polygon(map)
        polygon.points = Polygon.pointsAsCircle(center, radiusKm * 1000.0)

        val alphaFill = Color.argb(
            alpha,
            Color.red(fillColor),
            Color.green(fillColor),
            Color.blue(fillColor)
        )
        polygon.fillPaint.color = alphaFill
        polygon.outlinePaint.color = strokeColor
        polygon.outlinePaint.strokeWidth = 0f

        return polygon
    }

    private fun sstColorMap(normalized: Double): Int {
        val n = normalized.toFloat().coerceIn(0f, 1f)
        return when {
            n < 0.25f -> {
                val t = n / 0.25f
                Color.rgb((0 + t * 0).toInt(), (0 + t * 150).toInt(), (200 + t * 55).toInt())
            }
            n < 0.5f -> {
                val t = (n - 0.25f) / 0.25f
                Color.rgb((0 + t * 100).toInt(), (150 + t * 105).toInt(), (255 - t * 55).toInt())
            }
            n < 0.75f -> {
                val t = (n - 0.5f) / 0.25f
                Color.rgb((100 + t * 155).toInt(), (255 - t * 30).toInt(), (200 - t * 200).toInt())
            }
            else -> {
                val t = (n - 0.75f) / 0.25f
                Color.rgb(255, (225 - t * 225).toInt(), 0)
            }
        }
    }

    private fun showLoading(show: Boolean, message: String? = null) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (message != null) {
            tvLoadingStatus.text = message
        }
    }
}
