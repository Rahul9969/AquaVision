package com.rahul.aquavision.ui.fishing

import android.content.Context
import android.util.Log
import com.rahul.aquavision.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Repository for fetching fishing zone data from the backend API.
 * Includes offline caching via SharedPreferences and built-in
 * Indian subcontinent fallback data when the backend is unreachable.
 */
class FishingZoneRepository(private val context: Context) {

    companion object {
        private const val TAG = "FishingZoneRepo"
        private const val PREFS_NAME = "fishing_zone_cache"
        private const val KEY_HOTSPOTS_JSON = "cached_hotspots_json"
        private const val KEY_HEATMAP_JSON = "cached_heatmap_json"
        private const val KEY_LAST_FETCH_TIME = "last_fetch_time"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(75, TimeUnit.SECONDS)  // Render.com free tier cold start can take 60s
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the backend base URL from Constants.
     */
    private fun getBaseUrl(): String {
        return Constants.FISHING_ZONE_API_BASE_URL
    }

    /**
     * Fetch fishing zone hotspots from the API.
     * Falls back to cached data, then to built-in Indian subcontinent data.
     */
    suspend fun fetchFishingZones(lat: Double, lon: Double, radiusKm: Double = 200.0
    ): Result<FishingZoneResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/fishing-zones?lat=$lat&lon=$lon&radius_km=$radiusKm"
            Log.d(TAG, "Fetching fishing zones: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val parsed = parseFishingZoneResponse(body)

                // Cache the successful response
                prefs.edit()
                    .putString(KEY_HOTSPOTS_JSON, body)
                    .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                    .apply()

                Result.success(parsed)
            } else {
                Log.w(TAG, "API returned ${response.code}: ${response.message}")
                // Try cached data, then fallback
                getCachedFishingZones()
                    ?: Result.success(getIndianSubcontinentFallback())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
            // Try cached data, then fallback
            getCachedFishingZones()
                ?: Result.success(getIndianSubcontinentFallback())
        }
    }

    /**
     * Fetch ocean conditions heatmap grid from the API.
     */
    suspend fun fetchHeatmapData(lat: Double, lon: Double, radiusKm: Double = 100.0
    ): Result<HeatmapResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/ocean-conditions?lat=$lat&lon=$lon&radius_km=$radiusKm"
            Log.d(TAG, "Fetching heatmap data: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val parsed = parseHeatmapResponse(body)

                // Cache
                prefs.edit().putString(KEY_HEATMAP_JSON, body).apply()

                Result.success(parsed)
            } else {
                getCachedHeatmapData() ?: Result.failure(
                    Exception("Server error: ${response.code}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error for heatmap", e)
            getCachedHeatmapData() ?: Result.failure(e)
        }
    }

    /**
     * Get cached fishing zones if available and not expired.
     */
    private fun getCachedFishingZones(): Result<FishingZoneResponse>? {
        val lastFetch = prefs.getLong(KEY_LAST_FETCH_TIME, 0)
        if (System.currentTimeMillis() - lastFetch > CACHE_TTL_MS) return null

        val cachedJson = prefs.getString(KEY_HOTSPOTS_JSON, null) ?: return null
        return try {
            Result.success(parseFishingZoneResponse(cachedJson))
        } catch (e: Exception) {
            null
        }
    }

    private fun getCachedHeatmapData(): Result<HeatmapResponse>? {
        val cachedJson = prefs.getString(KEY_HEATMAP_JSON, null) ?: return null
        return try {
            Result.success(parseHeatmapResponse(cachedJson))
        } catch (e: Exception) {
            null
        }
    }

    // =====================================================
    // Indian Subcontinent Fallback Data
    // =====================================================

    /**
     * Generate built-in fallback hotspot data for the Indian subcontinent.
     * Based on INCOIS PFZ (Potential Fishing Zone) patterns.
     * This ensures the app ALWAYS shows meaningful data, even offline.
     */
    private fun getIndianSubcontinentFallback(): FishingZoneResponse {
        Log.i(TAG, "Using Indian subcontinent fallback data")

        val hotspots = listOf(
            // West Coast (Arabian Sea)
            createFallbackHotspot(
                "hz_goa01", 15.85, 73.25, 0.82, "HIGH",
                listOf("Mackerel", "Sardine", "Pomfret"),
                27.5, 1.2, -80.0,
                "🟢 High probability of fish concentration. Continental shelf (80m depth). Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_mum01", 19.05, 72.10, 0.78, "HIGH",
                listOf("Bombay Duck", "Prawn", "Ribbon Fish"),
                28.0, 0.8, -60.0,
                "🟢 High probability of fish concentration. Rich feeding grounds near Mumbai coast. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_ker01", 10.20, 75.50, 0.85, "HIGH",
                listOf("Oil Sardine", "Mackerel", "Indian Anchovy"),
                27.0, 1.5, -45.0,
                "🟢 High probability of fish concentration. Upwelling zone with rich nutrients. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_guj01", 21.50, 69.50, 0.72, "MEDIUM",
                listOf("Pomfret", "Croaker", "Catfish"),
                28.5, 0.6, -35.0,
                "🟡 Moderate fish concentration expected. Gujarat continental shelf. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_mng01", 12.80, 74.40, 0.76, "HIGH",
                listOf("Mackerel", "Oil Sardine", "Prawn"),
                27.5, 1.0, -70.0,
                "🟢 High probability of fish concentration. Mangalore shelf area. Best fishing time: 5:00 AM – 10:00 AM."
            ),

            // East Coast (Bay of Bengal)
            createFallbackHotspot(
                "hz_chn01", 13.30, 80.80, 0.73, "MEDIUM",
                listOf("Tuna", "Seer Fish", "Squid"),
                28.5, 0.5, -100.0,
                "🟡 Moderate fish concentration expected. Deep water target pelagic species. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_viz01", 17.80, 83.80, 0.70, "MEDIUM",
                listOf("Ribbon Fish", "Threadfin Bream", "Prawn"),
                28.0, 0.7, -90.0,
                "🟡 Moderate fish concentration expected. Visakhapatnam coast. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_odi01", 20.50, 87.00, 0.68, "MEDIUM",
                listOf("Hilsa", "Prawn", "Croaker"),
                27.5, 0.9, -55.0,
                "🟡 Moderate fish concentration expected. Rich Hilsa fishing grounds. Best fishing time: 5:00 AM – 10:00 AM."
            ),
            createFallbackHotspot(
                "hz_man01", 8.70, 78.80, 0.75, "HIGH",
                listOf("Lobster", "Cuttlefish", "Sole"),
                28.0, 0.8, -30.0,
                "🟢 High probability of fish concentration. Gulf of Mannar region. Best fishing time: 5:00 AM – 10:00 AM."
            ),

            // Deep water
            createFallbackHotspot(
                "hz_lak01", 11.50, 72.50, 0.65, "MEDIUM",
                listOf("Tuna", "Seer Fish", "Squid"),
                29.0, 0.3, -1500.0,
                "🟡 Moderate fish concentration expected. Lakshadweep deep water — target pelagic species. Best fishing time: 5:00 AM – 10:00 AM."
            )
        )

        return FishingZoneResponse(
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
            region = "Indian Subcontinent",
            center_lat = 15.0,
            center_lon = 76.0,
            radius_km = 500.0,
            hotspots = hotspots,
            data_sources = listOf("INCOIS PFZ Reference Data", "Seasonal Climatology"),
            last_updated = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
        )
    }

    private fun createFallbackHotspot(
        id: String, lat: Double, lon: Double,
        hsiScore: Double, confidence: String,
        species: List<String>,
        sst: Double, chl: Double, depth: Double,
        advisory: String
    ): FishingHotspot {
        return FishingHotspot(
            id = id,
            lat = lat,
            lon = lon,
            radius_km = 15.0,
            hsi_score = hsiScore,
            confidence = confidence,
            predicted_species = species,
            conditions = OceanConditions(
                sst_celsius = sst,
                chlorophyll_mgm3 = chl,
                chlorophyll_gradient = 0.05,
                sst_gradient = 0.08,
                depth_meters = depth,
                current_speed_ms = 0.15,
                current_direction_deg = 180.0,
                ssh_meters = 0.01,
                salinity_psu = 34.5,
                dissolved_oxygen_ml = 5.0
            ),
            advisory = advisory
        )
    }

    // =====================================================
    // JSON Parsing
    // =====================================================

    private fun parseFishingZoneResponse(json: String): FishingZoneResponse {
        val obj = JSONObject(json)
        val hotspotsArray = obj.getJSONArray("hotspots")
        val hotspots = mutableListOf<FishingHotspot>()

        for (i in 0 until hotspotsArray.length()) {
            hotspots.add(parseHotspot(hotspotsArray.getJSONObject(i)))
        }

        val dataSources = mutableListOf<String>()
        val dsArray = obj.optJSONArray("data_sources")
        if (dsArray != null) {
            for (i in 0 until dsArray.length()) {
                dataSources.add(dsArray.getString(i))
            }
        }

        return FishingZoneResponse(
            date = obj.optString("date", ""),
            region = obj.optString("region", "Unknown"),
            center_lat = obj.optDouble("center_lat", 0.0),
            center_lon = obj.optDouble("center_lon", 0.0),
            radius_km = obj.optDouble("radius_km", 200.0),
            hotspots = hotspots,
            data_sources = dataSources,
            last_updated = obj.optString("last_updated", "")
        )
    }

    private fun parseHotspot(obj: JSONObject): FishingHotspot {
        val condObj = obj.getJSONObject("conditions")
        val conditions = OceanConditions(
            sst_celsius = condObj.optDoubleOrNull("sst_celsius"),
            chlorophyll_mgm3 = condObj.optDoubleOrNull("chlorophyll_mgm3"),
            chlorophyll_gradient = condObj.optDoubleOrNull("chlorophyll_gradient"),
            sst_gradient = condObj.optDoubleOrNull("sst_gradient"),
            depth_meters = condObj.optDoubleOrNull("depth_meters"),
            current_speed_ms = condObj.optDoubleOrNull("current_speed_ms"),
            current_direction_deg = condObj.optDoubleOrNull("current_direction_deg"),
            ssh_meters = condObj.optDoubleOrNull("ssh_meters"),
            salinity_psu = condObj.optDoubleOrNull("salinity_psu"),
            dissolved_oxygen_ml = condObj.optDoubleOrNull("dissolved_oxygen_ml")
        )

        val speciesArray = obj.optJSONArray("predicted_species") ?: JSONArray()
        val species = mutableListOf<String>()
        for (i in 0 until speciesArray.length()) {
            species.add(speciesArray.getString(i))
        }

        return FishingHotspot(
            id = obj.optString("id", ""),
            lat = obj.optDouble("lat", 0.0),
            lon = obj.optDouble("lon", 0.0),
            radius_km = obj.optDouble("radius_km", 15.0),
            hsi_score = obj.optDouble("hsi_score", 0.0),
            confidence = obj.optString("confidence", "LOW"),
            predicted_species = species,
            conditions = conditions,
            advisory = obj.optString("advisory", "")
        )
    }

    private fun parseHeatmapResponse(json: String): HeatmapResponse {
        val obj = JSONObject(json)
        val cellsArray = obj.getJSONArray("cells")
        val cells = mutableListOf<HeatmapCell>()

        for (i in 0 until cellsArray.length()) {
            val c = cellsArray.getJSONObject(i)
            cells.add(HeatmapCell(
                lat = c.optDouble("lat", 0.0),
                lon = c.optDouble("lon", 0.0),
                sst = c.optDoubleOrNull("sst"),
                chlorophyll = c.optDoubleOrNull("chlorophyll"),
                hsi = c.optDoubleOrNull("hsi"),
                depth = c.optDoubleOrNull("depth")
            ))
        }

        return HeatmapResponse(
            date = obj.optString("date", ""),
            center_lat = obj.optDouble("center_lat", 0.0),
            center_lon = obj.optDouble("center_lon", 0.0),
            grid_resolution_deg = obj.optDouble("grid_resolution_deg", 0.1),
            cells = cells,
            min_sst = obj.optDoubleOrNull("min_sst"),
            max_sst = obj.optDoubleOrNull("max_sst"),
            min_chlorophyll = obj.optDoubleOrNull("min_chlorophyll"),
            max_chlorophyll = obj.optDoubleOrNull("max_chlorophyll")
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (this.has(key) && !this.isNull(key)) this.optDouble(key) else null
    }
}
