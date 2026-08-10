package com.rahul.aquavision.geofence

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

    data class PolygonData(
        val outer: List<Pair<Double, Double>>,
        val holes: List<List<Pair<Double, Double>>>
    )

    private val landPolygons: List<PolygonData>
    private val territorialPolygons: List<PolygonData>
    private val eezPolygons: List<PolygonData>

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
    private fun parseAllGeoJsonPolygons(geoJsonString: String): List<PolygonData> {
        val polygons = mutableListOf<PolygonData>()

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
                    polygons.add(parsePolygonNode(coordinates))
                }
                "MultiPolygon" -> {
                    for (p in 0 until coordinates.length()) {
                        polygons.add(parsePolygonNode(coordinates.getJSONArray(p)))
                    }
                }
            }
        }

        return polygons
    }

    private fun parsePolygonNode(polygonArray: org.json.JSONArray): PolygonData {
        val outerRing = mutableListOf<Pair<Double, Double>>()
        val outerCoords = polygonArray.getJSONArray(0)
        for (i in 0 until outerCoords.length()) {
            val c = outerCoords.getJSONArray(i)
            outerRing.add(Pair(c.getDouble(0), c.getDouble(1)))
        }

        val holes = mutableListOf<List<Pair<Double, Double>>>()
        for (h in 1 until polygonArray.length()) {
            val holeRing = mutableListOf<Pair<Double, Double>>()
            val holeCoords = polygonArray.getJSONArray(h)
            for (i in 0 until holeCoords.length()) {
                val c = holeCoords.getJSONArray(i)
                holeRing.add(Pair(c.getDouble(0), c.getDouble(1)))
            }
            if (holeRing.isNotEmpty()) holes.add(holeRing)
        }
        return PolygonData(outerRing, holes)
    }

    /**
     * Check if a point is inside ANY of the polygon rings.
     */
    private fun isPointInAnyPolygon(
        longitude: Double,
        latitude: Double,
        polygons: List<PolygonData>
    ): Boolean {
        for (poly in polygons) {
            if (isPointInPolygonRing(longitude, latitude, poly.outer)) {
                // If it is in the outer boundary, verify it's NOT inside any hole (e.g. lake/lagoon)
                var inHole = false
                for (hole in poly.holes) {
                    if (isPointInPolygonRing(longitude, latitude, hole)) {
                        inHole = true
                        break
                    }
                }
                if (!inHole) return true
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
    private fun isPointInPolygonRing(
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
        polygons: List<PolygonData>
    ): Double {
        var minDistance = Double.MAX_VALUE

        for (poly in polygons) {
            minDistance = min(minDistance, getDistanceToRing(latitude, longitude, poly.outer))
            for (hole in poly.holes) {
                minDistance = min(minDistance, getDistanceToRing(latitude, longitude, hole))
            }
        }

        return minDistance
    }

    private fun getDistanceToRing(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Double {
        if (ring.isEmpty()) return Double.MAX_VALUE
        var minDist = Double.MAX_VALUE
        var j = ring.size - 1
        for (i in ring.indices) {
            val p1 = ring[j]
            val p2 = ring[i]
            val dist = distanceToSegment(lat, lon, p1.second, p1.first, p2.second, p2.first)
            if (dist < minDist) minDist = dist
            j = i
        }
        return minDist
    }

    /**
     * Calculates the shortest distance from point P to line segment A-B.
     * Uses equirectangular projection for speed and accuracy over small distances.
     */
    private fun distanceToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0 // Earth radius in km
        val cosPlat = cos(Math.toRadians(pLat))
        
        // Project A and B relative to P(0,0) into km
        val ax = Math.toRadians(aLon - pLon) * cosPlat * r
        val ay = Math.toRadians(aLat - pLat) * r
        val bx = Math.toRadians(bLon - pLon) * cosPlat * r
        val by = Math.toRadians(bLat - pLat) * r
        
        val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        if (l2 == 0.0) return sqrt(ax*ax + ay*ay) // A == B
        
        // Calculate projection scalar t
        var t = (-(ax * (bx - ax) + ay * (by - ay))) / l2
        t = max(0.0, min(1.0, t)) // Clamp to segment
        
        val projX = ax + t * (bx - ax)
        val projY = ay + t * (by - ay)
        
        return sqrt(projX*projX + projY*projY)
    }
}
