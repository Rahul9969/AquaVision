package com.rahul.aquavision.ui.fishing

/**
 * Data classes for the Fishing Zone feature.
 * These map to the Python backend API responses.
 */

data class OceanConditions(
    val sst_celsius: Double?,
    val chlorophyll_mgm3: Double?,
    val chlorophyll_gradient: Double?,
    val sst_gradient: Double?,
    val depth_meters: Double?,
    val current_speed_ms: Double?,
    val current_direction_deg: Double?,
    val ssh_meters: Double?,
    val salinity_psu: Double?,
    val dissolved_oxygen_ml: Double?
)

data class FishingHotspot(
    val id: String,
    val lat: Double,
    val lon: Double,
    val radius_km: Double,
    val hsi_score: Double,
    val confidence: String,
    val predicted_species: List<String>,
    val conditions: OceanConditions,
    val advisory: String
)

data class FishingZoneResponse(
    val date: String,
    val region: String,
    val center_lat: Double,
    val center_lon: Double,
    val radius_km: Double,
    val hotspots: List<FishingHotspot>,
    val data_sources: List<String>,
    val last_updated: String
)

data class HeatmapCell(
    val lat: Double,
    val lon: Double,
    val sst: Double?,
    val chlorophyll: Double?,
    val hsi: Double?,
    val depth: Double?
)

data class HeatmapResponse(
    val date: String,
    val center_lat: Double,
    val center_lon: Double,
    val grid_resolution_deg: Double,
    val cells: List<HeatmapCell>,
    val min_sst: Double?,
    val max_sst: Double?,
    val min_chlorophyll: Double?,
    val max_chlorophyll: Double?
)
