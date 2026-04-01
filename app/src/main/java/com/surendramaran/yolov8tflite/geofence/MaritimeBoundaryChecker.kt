package com.surendramaran.yolov8tflite.geofence

import android.content.Context
import android.location.Location
import android.util.Log
import org.json.JSONObject
import kotlin.math.*

/**
 * Maritime zone status enum representing different Indian maritime boundaries.
 * Works completely offline using pre-loaded GeoJSON boundary data.
 */
enum class WaterStatus(
    val displayName: String,
    val emoji: String,
    val description: String,
    val legalStatus: String,
    val colorHex: Long
) {
    ON_LAND(
        "On Indian Land",
        "🟤",
        "You are on land - Not in maritime zone",
        "Not applicable - No fishing zone",
        0xFF795548
    ),
    TERRITORIAL_WATERS(
        "Indian Territorial Waters",
        "🟢",
        "Within 12 nautical miles (22 km) from coast",
        "✅ LEGAL for Indian fishermen with proper license\n⚠️ First 5 km reserved for traditional craft only",
        0xFF4CAF50
    ),
    EEZ(
        "Indian Exclusive Economic Zone",
        "🟡",
        "12-200 nautical miles (22-370 km) from coast",
        "✅ LEGAL for Indian fishermen with Access Pass\n⚠️ Mechanized vessels need registration",
        0xFFFFC107
    ),
    OUTSIDE_INDIAN_WATERS(
        "Outside Indian Waters",
        "🔴",
        "Beyond 200 NM (370 km) - International waters",
        "⚠️ CAUTION: High seas - Special permits required\n❌ Most Indian fishing vessels NOT authorized",
        0xFFF44336
    ),
    UNKNOWN(
        "Location Unknown",
        "⚪",
        "Enable GPS to check maritime status",
        "Enable location to check fishing regulations",
        0xFF9E9E9E
    )
}

/**
 * Offline maritime boundary checker using ray-casting point-in-polygon algorithm.
 * Loads simplified GeoJSON boundary data from app assets at initialization.
 *
 * Boundaries checked (in priority order):
 * 1. India Land Boundary
 * 2. Territorial Waters (12 NM / 22 km from coast)
 * 3. Exclusive Economic Zone (200 NM / 370 km from coast)
 * 4. Outside Indian Waters (international/foreign waters)
 */
class MaritimeBoundaryChecker(context: Context) {

    // Store ALL polygon rings (not just the largest) for accurate multi-island checks
    private val landPolygons: List<List<Pair<Double, Double>>>
    private val territorialPolygons: List<List<Pair<Double, Double>>>
    private val eezPolygons: List<List<Pair<Double, Double>>>

    companion object {
        private const val TAG = "MaritimeBoundary"
    }

    init {
        try {
            // Load Land Boundary
            val landJson = context.assets.open("india_land_simplified.geojson")
                .bufferedReader()
                .use { it.readText() }
            landPolygons = parseAllGeoJsonPolygons(landJson)
            Log.d(TAG, "✅ Loaded land boundary: ${landPolygons.size} polygon rings")

            // Load Territorial Waters (12 NM)
            val territorialJson = context.assets.open("india_territorial_12nm_simplified.geojson")
                .bufferedReader()
                .use { it.readText() }
            territorialPolygons = parseAllGeoJsonPolygons(territorialJson)
            Log.d(TAG, "✅ Loaded territorial waters: ${territorialPolygons.size} polygon rings")

            // Load EEZ (200 NM)
            val eezJson = context.assets.open("india_eez_simplified.geojson")
                .bufferedReader()
                .use { it.readText() }
            eezPolygons = parseAllGeoJsonPolygons(eezJson)
            Log.d(TAG, "✅ Loaded EEZ: ${eezPolygons.size} polygon rings")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading boundaries", e)
            throw e
        }
    }

    /**
     * Parse ALL polygon rings from a GeoJSON FeatureCollection.
     * Handles Polygon, MultiPolygon geometry types.
     * Returns a list of polygon rings (each ring is a list of lon/lat pairs).
     */
    private fun parseAllGeoJsonPolygons(geoJsonString: String): List<List<Pair<Double, Double>>> {
        val allRings = mutableListOf<List<Pair<Double, Double>>>()

        val parsed = JSONObject(geoJsonString)
        val features = parsed.getJSONArray("features")

        if (features.length() == 0) {
            Log.e(TAG, "No features found in GeoJSON")
            return emptyList()
        }

        for (f in 0 until features.length()) {
            val geometry = features.getJSONObject(f).getJSONObject("geometry")
            val type = geometry.getString("type")
            val coordinates = geometry.getJSONArray("coordinates")

            when (type) {
                "Polygon" -> {
                    // A Polygon has an array of rings; first ring is outer boundary
                    val ring = coordinates.getJSONArray(0)
                    val points = mutableListOf<Pair<Double, Double>>()
                    for (i in 0 until ring.length()) {
                        val coord = ring.getJSONArray(i)
                        val lon = coord.getDouble(0)
                        val lat = coord.getDouble(1)
                        points.add(Pair(lon, lat))
                    }
                    if (points.isNotEmpty()) allRings.add(points)
                }
                "MultiPolygon" -> {
                    // MultiPolygon: array of polygons, each with array of rings
                    for (p in 0 until coordinates.length()) {
                        val polygon = coordinates.getJSONArray(p)
                        val ring = polygon.getJSONArray(0) // outer ring
                        val points = mutableListOf<Pair<Double, Double>>()
                        for (i in 0 until ring.length()) {
                            val coord = ring.getJSONArray(i)
                            val lon = coord.getDouble(0)
                            val lat = coord.getDouble(1)
                            points.add(Pair(lon, lat))
                        }
                        if (points.isNotEmpty()) allRings.add(points)
                    }
                }
            }
        }

        return allRings
    }

    /**
     * Check if a point is inside ANY of the polygon rings.
     */
    private fun isPointInAnyPolygon(
        longitude: Double,
        latitude: Double,
        polygons: List<List<Pair<Double, Double>>>
    ): Boolean {
        for (polygon in polygons) {
            if (isPointInPolygon(longitude, latitude, polygon)) {
                return true
            }
        }
        return false
    }

    /**
     * Check location and return maritime status.
     * Priority: Land > Territorial > EEZ > Outside
     * Works 100% OFFLINE using pre-loaded GeoJSON data.
     */
    fun checkLocation(latitude: Double, longitude: Double): WaterStatus {
        try {
            // 1. Check if on land first
            if (isPointInAnyPolygon(longitude, latitude, landPolygons)) {
                Log.d(TAG, "Location ($latitude, $longitude) is ON LAND")
                return WaterStatus.ON_LAND
            }

            // 2. Check territorial waters (12NM from coast)
            if (isPointInAnyPolygon(longitude, latitude, territorialPolygons)) {
                Log.d(TAG, "Location ($latitude, $longitude) is in TERRITORIAL WATERS")
                return WaterStatus.TERRITORIAL_WATERS
            }

            // 3. Check EEZ (up to 200NM)
            if (isPointInAnyPolygon(longitude, latitude, eezPolygons)) {
                Log.d(TAG, "Location ($latitude, $longitude) is in EEZ")
                return WaterStatus.EEZ
            }

            // 4. Outside all Indian zones (international waters)
            Log.d(TAG, "Location ($latitude, $longitude) is OUTSIDE Indian waters")
            return WaterStatus.OUTSIDE_INDIAN_WATERS

        } catch (e: Exception) {
            Log.e(TAG, "Error checking location", e)
            return WaterStatus.UNKNOWN
        }
    }

    fun checkLocation(location: Location): WaterStatus {
        return checkLocation(location.latitude, location.longitude)
    }

    /**
     * Ray-casting algorithm for point-in-polygon check.
     * This is the core algorithm that determines if a GPS coordinate
     * falls inside a polygon boundary. Works offline with no network needed.
     */
    private fun isPointInPolygon(
        longitude: Double,
        latitude: Double,
        polygon: List<Pair<Double, Double>>
    ): Boolean {
        if (polygon.isEmpty()) return false

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]  // xi=lon, yi=lat
            val (xj, yj) = polygon[j]

            val intersect = ((yi > latitude) != (yj > latitude)) &&
                    (longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi)

            if (intersect) inside = !inside
            j = i
        }

        return inside
    }

    /**
     * Get distance to nearest point on coast (land boundary)
     */
    fun getDistanceToCoast(latitude: Double, longitude: Double): Double {
        return getDistanceToBoundary(latitude, longitude, landPolygons)
    }

    fun getDistanceToTerritorialWaters(latitude: Double, longitude: Double): Double {
        return getDistanceToBoundary(latitude, longitude, territorialPolygons)
    }

    fun getDistanceToEEZ(latitude: Double, longitude: Double): Double {
        return getDistanceToBoundary(latitude, longitude, eezPolygons)
    }

    private fun getDistanceToBoundary(
        latitude: Double,
        longitude: Double,
        polygons: List<List<Pair<Double, Double>>>
    ): Double {
        var minDistance = Double.MAX_VALUE

        for (polygon in polygons) {
            for ((boundaryLon, boundaryLat) in polygon) {
                val distance = haversineDistance(
                    latitude, longitude,
                    boundaryLat, boundaryLon
                )
                if (distance < minDistance) {
                    minDistance = distance
                }
            }
        }

        return minDistance
    }

    /**
     * Haversine formula for accurate distance calculation between two GPS points.
     * Returns distance in kilometers.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }
}
