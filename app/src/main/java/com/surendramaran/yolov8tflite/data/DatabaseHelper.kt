package com.surendramaran.yolov8tflite.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.surendramaran.yolov8tflite.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

// ... (Keep HistoryItem data class as is) ...
data class HistoryItem(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val title: String,
    val details: String,
    val lat: Double,
    val lng: Double,
    val placeName: String,
    val type: Int,
    val isSynced: Int = 0
)

class DatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    // ... (Keep onCreate, onUpgrade, insertLog, insertDetection, getUnsyncedLogs, markAsSynced, getTotalBiomass, getSizeDistribution, getHourlyActivity methods as is) ...
    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_NAME + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TIMESTAMP + " INTEGER,"
                + COLUMN_IMAGE_PATH + " TEXT,"
                + COLUMN_TITLE + " TEXT,"
                + COLUMN_DETAILS + " TEXT,"
                + COLUMN_LAT + " REAL,"
                + COLUMN_LNG + " REAL,"
                + COLUMN_PLACE_NAME + " TEXT,"
                + COLUMN_TYPE + " INTEGER DEFAULT 0,"
                + COLUMN_SYNCED + " INTEGER DEFAULT 0" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertLog(timestamp: Long, imagePath: String, title: String, details: String, lat: Double, lng: Double, placeName: String, type: Int): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TIMESTAMP, timestamp)
        values.put(COLUMN_IMAGE_PATH, imagePath)
        values.put(COLUMN_TITLE, title)
        values.put(COLUMN_DETAILS, details)
        values.put(COLUMN_LAT, lat)
        values.put(COLUMN_LNG, lng)
        values.put(COLUMN_PLACE_NAME, placeName)
        values.put(COLUMN_TYPE, type)
        values.put(COLUMN_SYNCED, 0)
        return db.insert(TABLE_NAME, null, values)
    }

    fun insertDetection(timestamp: Long, imagePath: String, fishCount: String, details: String, lat: Double, lng: Double, placeName: String): Long {
        return insertLog(timestamp, imagePath, fishCount, details, lat, lng, placeName, TYPE_DETECTION)
    }

    fun getUnsyncedLogs(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_SYNCED = 0", null)

        if (cursor.moveToFirst()) {
            do {
                list.add(extractItem(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun markAsSynced(id: Int) {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_SYNCED, 1)
        db.update(TABLE_NAME, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun getTotalBiomass(startTime: Long = 0): Double {
        val db = this.readableDatabase
        var totalWeightGrams = 0.0

        val cursor = db.rawQuery("SELECT $COLUMN_DETAILS FROM $TABLE_NAME WHERE $COLUMN_TYPE = $TYPE_VOLUME AND $COLUMN_TIMESTAMP >= $startTime", null)
        val pattern = Pattern.compile("Est\\. Weight: (\\d+)g")

        if (cursor.moveToFirst()) {
            do {
                val details = cursor.getString(0) ?: ""
                val parts = details.split(";;;")
                for (part in parts) {
                    val matcher = pattern.matcher(part)
                    if (matcher.find()) {
                        totalWeightGrams += matcher.group(1)?.toDoubleOrNull() ?: 0.0
                    }
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return totalWeightGrams / 1000.0
    }

    // --- NEW: Size Distribution (Small, Medium, Large) ---
    fun getSizeDistribution(startTime: Long = 0): Triple<Int, Int, Int> {
        var small = 0
        var medium = 0
        var large = 0

        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_DETAILS FROM $TABLE_NAME WHERE $COLUMN_TYPE = $TYPE_VOLUME AND $COLUMN_TIMESTAMP >= $startTime", null)
        val pattern = Pattern.compile("Est\\. Weight: (\\d+)g")

        if (cursor.moveToFirst()) {
            do {
                val details = cursor.getString(0) ?: ""
                val parts = details.split(";;;")
                for (part in parts) {
                    val matcher = pattern.matcher(part)
                    if (matcher.find()) {
                        val weight = matcher.group(1)?.toDoubleOrNull() ?: 0.0
                        when {
                            weight < 500 -> small++
                            weight in 500.0..2000.0 -> medium++
                            else -> large++
                        }
                    }
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return Triple(small, medium, large)
    }

    // --- NEW: Hourly Activity (0-23) ---
    fun getHourlyActivity(startTime: Long = 0): Map<Int, Int> {
        val db = this.readableDatabase
        val map = mutableMapOf<Int, Int>()
        // Initialize all hours to 0
        for (i in 0..23) map[i] = 0

        val cursor = db.rawQuery("SELECT $COLUMN_TIMESTAMP FROM $TABLE_NAME WHERE $COLUMN_TIMESTAMP >= $startTime", null)
        val calendar = Calendar.getInstance()

        if (cursor.moveToFirst()) {
            do {
                val ts = cursor.getLong(0)
                calendar.timeInMillis = ts
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                map[hour] = map[hour]!! + 1
            } while (cursor.moveToNext())
        }
        cursor.close()
        return map
    }

    fun getWeeklyActivity(): Map<String, Int> {
        val db = this.readableDatabase
        val activityMap = LinkedHashMap<String, Int>()
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())

        calendar.add(Calendar.DAY_OF_YEAR, -6)
        for (i in 0..6) {
            val dayKey = dateFormat.format(calendar.time)
            activityMap[dayKey] = 0
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        val cursor = db.rawQuery("SELECT $COLUMN_TIMESTAMP, $COLUMN_TITLE FROM $TABLE_NAME WHERE $COLUMN_TYPE = $TYPE_DETECTION AND $COLUMN_TIMESTAMP > ?", arrayOf(sevenDaysAgo.toString()))

        if (cursor.moveToFirst()) {
            do {
                val ts = cursor.getLong(0)
                val title = cursor.getString(1)
                val dayKey = dateFormat.format(ts)

                var countForEntry = 0
                // --- FIXED PARSING FOR NEW FORMAT ---
                val parts = title.split(",")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.contains(":")) {
                        // Old format: "Rohu: 2" (or "Eyes: 5")
                        val entry = trimmed.split(":")
                        if (entry.size == 2) {
                            val name = entry[0].trim()
                            if (!name.equals("Eyes", ignoreCase = true)) {
                                countForEntry += entry[1].trim().toIntOrNull() ?: 0
                            }
                        }
                    } else {
                        // New format: "Rohu 85%" (One entry = One fish)
                        // Just ensure it's not "Eyes" or some other meta info if format changes
                        if (!trimmed.startsWith("Eyes", ignoreCase = true)) {
                            countForEntry += 1
                        }
                    }
                }
                // ------------------------------------

                if (activityMap.containsKey(dayKey)) {
                    activityMap[dayKey] = activityMap[dayKey]!! + countForEntry
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return activityMap
    }

    fun getAnalyticsStats(startTime: Long = 0): AnalyticsStats {
        val db = this.readableDatabase

        var totalCatch = 0
        var totalEyes = 0
        val speciesMap = mutableMapOf<String, Int>()

        val speciesCursor = db.rawQuery("SELECT $COLUMN_TITLE, $COLUMN_DETAILS FROM $TABLE_NAME WHERE $COLUMN_TYPE = $TYPE_DETECTION AND $COLUMN_TIMESTAMP >= $startTime", null)
        val eyesPattern = Pattern.compile("Eyes: (\\d+)")

        if (speciesCursor.moveToFirst()) {
            do {
                // 1. Parse Fish Count from Title
                val title = speciesCursor.getString(0) ?: ""

                // --- FIXED PARSING FOR NEW FORMAT ---
                val parts = title.split(",")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.contains(":")) {
                        // Old format handling: "Rohu: 2"
                        val entry = trimmed.split(":")
                        if (entry.size == 2) {
                            val name = entry[0].trim()
                            if (!name.equals("Eyes", ignoreCase = true)) {
                                val count = entry[1].trim().toIntOrNull() ?: 0
                                speciesMap[name] = speciesMap.getOrDefault(name, 0) + count
                                totalCatch += count
                            }
                        }
                    } else {
                        // New format handling: "Rohu 85%"
                        // 1. Remove percentage if present
                        var name = trimmed
                        val lastSpaceIndex = trimmed.lastIndexOf(' ')
                        if (lastSpaceIndex != -1) {
                            // Check if the part after space is like "85%"
                            val possibleConf = trimmed.substring(lastSpaceIndex + 1)
                            if (possibleConf.contains("%")) {
                                name = trimmed.substring(0, lastSpaceIndex).trim()
                            }
                        }

                        // 2. Count as 1
                        if (!name.startsWith("Eyes", ignoreCase = true)) {
                            speciesMap[name] = speciesMap.getOrDefault(name, 0) + 1
                            totalCatch += 1
                        }
                    }
                }
                // ------------------------------------

                // 2. Parse Eye Count from Details (remains valid)
                val details = speciesCursor.getString(1) ?: ""
                val matcher = eyesPattern.matcher(details)
                if (matcher.find()) {
                    totalEyes += matcher.group(1)?.toIntOrNull() ?: 0
                }

            } while (speciesCursor.moveToNext())
        }
        speciesCursor.close()

        var freshCount = 0
        var spoiledCount = 0
        val freshCursor = db.rawQuery("SELECT $COLUMN_DETAILS FROM $TABLE_NAME WHERE $COLUMN_TYPE = $TYPE_FRESHNESS AND $COLUMN_TIMESTAMP >= $startTime", null)
        if (freshCursor.moveToFirst()) {
            do {
                val details = freshCursor.getString(0)
                if (details.contains("Status: Fresh", ignoreCase = true) || details.contains("Status: FRESH", ignoreCase = true)) {
                    freshCount++
                } else if (details.contains("Not Fresh", ignoreCase = true) || details.contains("NOT FRESH", ignoreCase = true)) {
                    spoiledCount++
                }
            } while (freshCursor.moveToNext())
        }
        freshCursor.close()

        return AnalyticsStats(totalCatch, speciesMap, freshCount, spoiledCount, totalEyes)
    }

    fun getRecentLogs(limit: Int): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_TYPE IN ($TYPE_DETECTION, $TYPE_VOLUME) ORDER BY $COLUMN_TIMESTAMP DESC LIMIT $limit", null)
        if (cursor.moveToFirst()) {
            do { list.add(extractItem(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getTopLocations(): List<Pair<String, Int>> {
        val list = mutableListOf<Pair<String, Int>>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_PLACE_NAME, COUNT(*) as count FROM $TABLE_NAME WHERE $COLUMN_PLACE_NAME != '${context.getString(R.string.unknown)}' GROUP BY $COLUMN_PLACE_NAME ORDER BY count DESC LIMIT 5", null)
        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(0)
                val count = cursor.getInt(1)
                list.add(Pair(name, count))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    data class AnalyticsStats(
        val totalCatch: Int,
        val speciesBreakdown: Map<String, Int>,
        val freshCount: Int,
        val spoiledCount: Int,
        val totalEyes: Int = 0
    )

    fun getHistoryByType(type: Int): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COLUMN_TYPE = ? ORDER BY $COLUMN_TIMESTAMP DESC", arrayOf(type.toString()))
        if (cursor.moveToFirst()) {
            do { list.add(extractItem(cursor)) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    private fun extractItem(cursor: Cursor): HistoryItem {
        val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
        val imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))

        val titleIndex = if (cursor.getColumnIndex(COLUMN_TITLE) != -1) cursor.getColumnIndex(COLUMN_TITLE) else cursor.getColumnIndex("fish_count")
        val title = cursor.getString(titleIndex) ?: ""

        val details = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETAILS))
        val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
        val lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LNG))

        val placeNameIndex = cursor.getColumnIndex(COLUMN_PLACE_NAME)
        val placeName = if (placeNameIndex != -1) cursor.getString(placeNameIndex) else context.getString(R.string.unknown)

        val typeIndex = cursor.getColumnIndex(COLUMN_TYPE)
        val itemType = if(typeIndex != -1) cursor.getInt(typeIndex) else 0

        val syncedIndex = cursor.getColumnIndex(COLUMN_SYNCED)
        val synced = if(syncedIndex != -1) cursor.getInt(syncedIndex) else 0

        return HistoryItem(id, timestamp, imagePath, title, details, lat, lng, placeName, itemType, synced)
    }

    companion object {
        private const val DATABASE_VERSION = 5
        private const val DATABASE_NAME = "FishDetectionDB"
        const val TABLE_NAME = "detections"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DETAILS = "details"
        const val COLUMN_LAT = "latitude"
        const val COLUMN_LNG = "longitude"
        const val COLUMN_PLACE_NAME = "place_name"
        const val COLUMN_TYPE = "type"
        const val COLUMN_SYNCED = "is_synced"

        const val TYPE_DETECTION = 0
        const val TYPE_FRESHNESS = 1
        const val TYPE_VOLUME = 2
    }
}