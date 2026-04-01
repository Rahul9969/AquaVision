package com.surendramaran.yolov8tflite.ui.fishing

import kotlin.math.*

data class FishLandingCentre(
    val id: String,
    val name: String,
    val district: String,
    val state: String,
    val lat: Double,
    val lon: Double
)

data class FlcProximityResult(
    val flc: FishLandingCentre,
    val distanceKm: Double,
    val bearingDeg: Double,
    val direction: String   // e.g. "SW", "NE"
)

object FishLandingCentreData {

    val ALL_FLCS: List<FishLandingCentre> = listOf(

        // ── MAHARASHTRA ──────────────────────────────────────────────────────
        FishLandingCentre("mh_vasai",      "Vasai Creek",         "Palghar",      "MAHARASHTRA", 19.3720, 72.8380),
        FishLandingCentre("mh_versova",    "Versova",             "Mumbai",       "MAHARASHTRA", 19.1410, 72.8110),
        FishLandingCentre("mh_bhaucha",    "Bhaucha Dhakka",      "Mumbai",       "MAHARASHTRA", 18.9350, 72.8450),
        FishLandingCentre("mh_sassoon",    "Sassoon Dock",        "Mumbai",       "MAHARASHTRA", 18.9100, 72.8290),
        FishLandingCentre("mh_thane",      "Thane Creek",         "Thane",        "MAHARASHTRA", 19.2183, 72.9780),
        FishLandingCentre("mh_alibag",     "Alibaug Jetty",       "Raigad",       "MAHARASHTRA", 18.6420, 72.8725),
        FishLandingCentre("mh_murud",      "Murud",               "Raigad",       "MAHARASHTRA", 18.3320, 72.9600),
        FishLandingCentre("mh_dabhol",     "Dabhol",              "Ratnagiri",    "MAHARASHTRA", 17.5870, 73.1790),
        FishLandingCentre("mh_ratnagiri",  "Ratnagiri Fishing",   "Ratnagiri",    "MAHARASHTRA", 16.9943, 73.3003),
        FishLandingCentre("mh_malvan",     "Malvan",              "Sindhudurg",   "MAHARASHTRA", 16.0570, 73.4680),
        FishLandingCentre("mh_devgad",     "Devgad",              "Sindhudurg",   "MAHARASHTRA", 16.3820, 73.3820),
        FishLandingCentre("mh_kankavali",  "Kankavali",           "Sindhudurg",   "MAHARASHTRA", 16.5820, 73.7050),

        // ── GUJARAT ──────────────────────────────────────────────────────────
        FishLandingCentre("gj_veraval",    "Veraval",             "Gir Somnath",  "GUJARAT",     20.9070, 70.3670),
        FishLandingCentre("gj_mangrol",    "Mangrol",             "Junagadh",     "GUJARAT",     21.1210, 70.1160),
        FishLandingCentre("gj_porbandar",  "Porbandar",           "Porbandar",    "GUJARAT",     21.6440, 69.6040),
        FishLandingCentre("gj_dwarka",     "Dwarka",              "Devbhumi",     "GUJARAT",     22.2374, 68.9676),
        FishLandingCentre("gj_okha",       "Okha",                "Devbhumi",     "GUJARAT",     22.4659, 69.0710),
        FishLandingCentre("gj_mandvi",     "Mandvi",              "Kutch",        "GUJARAT",     22.8330, 69.3530),
        FishLandingCentre("gj_mundra",     "Mundra",              "Kutch",        "GUJARAT",     22.8394, 69.7222),
        FishLandingCentre("gj_jakhau",     "Jakhau",              "Kutch",        "GUJARAT",     23.2091, 68.7174),
        FishLandingCentre("gj_surat",      "Surat Fishing",       "Surat",        "GUJARAT",     21.1702, 72.8311),

        // ── GOA ──────────────────────────────────────────────────────────────
        FishLandingCentre("ga_panaji",     "Panaji Jetty",        "North Goa",    "GOA",         15.4989, 73.8278),
        FishLandingCentre("ga_margao",     "Margao Market",       "South Goa",    "GOA",         15.2832, 73.9862),
        FishLandingCentre("ga_vasco",      "Vasco Fishing",       "South Goa",    "GOA",         15.3980, 73.8120),
        FishLandingCentre("ga_chapora",    "Chapora",             "North Goa",    "GOA",         15.5990, 73.7400),

        // ── KARNATAKA ────────────────────────────────────────────────────────
        FishLandingCentre("ka_mangaluru",  "Mangaluru Old Port",  "Dakshina",     "KARNATAKA",   12.8650, 74.8420),
        FishLandingCentre("ka_malpe",      "Malpe Harbour",       "Udupi",        "KARNATAKA",   13.3480, 74.7140),
        FishLandingCentre("ka_karwar",     "Karwar",              "Uttara",       "KARNATAKA",   14.8020, 74.1290),
        FishLandingCentre("ka_kumta",      "Kumta",               "Uttara",       "KARNATAKA",   14.4280, 74.4150),
        FishLandingCentre("ka_honavar",    "Honavar",             "Uttara",       "KARNATAKA",   14.2790, 74.4440),
        FishLandingCentre("ka_bhatkal",    "Bhatkal",             "Uttara",       "KARNATAKA",   13.9830, 74.5560),
        FishLandingCentre("ka_kundapur",   "Kundapur",            "Udupi",        "KARNATAKA",   13.6290, 74.6900),

        // ── KERALA ───────────────────────────────────────────────────────────
        FishLandingCentre("kl_kochi",      "Kochi Matsyafed",     "Ernakulam",    "KERALA",      9.9312,  76.2673),
        FishLandingCentre("kl_kozhikode",  "Kozhikode Beach",     "Kozhikode",    "KERALA",      11.2588, 75.7804),
        FishLandingCentre("kl_tvm",        "Thiruvananthapuram",  "TVM",          "KERALA",      8.5241,  76.9366),
        FishLandingCentre("kl_vizhinjam",  "Vizhinjam",           "TVM",          "KERALA",      8.3838,  77.0000),
        FishLandingCentre("kl_alappuzha",  "Alappuzha",           "Alappuzha",    "KERALA",      9.4981,  76.3388),
        FishLandingCentre("kl_thrissur",   "Thrissur",            "Thrissur",     "KERALA",      10.5276, 76.2144),
        FishLandingCentre("kl_kasaragod",  "Kasaragod",           "Kasaragod",    "KERALA",      12.4996, 74.9869),
        FishLandingCentre("kl_kannur",     "Kannur",              "Kannur",       "KERALA",      11.8745, 75.3704),

        // ── TAMIL NADU ───────────────────────────────────────────────────────
        FishLandingCentre("tn_kasimedu",   "Kasimedu (Chennai)",  "Chennai",      "TAMIL NADU",  13.1390, 80.3020),
        FishLandingCentre("tn_tuticorin",  "Tuticorin Harbour",   "Thoothukudi",  "TAMIL NADU",  8.7642,  78.1348),
        FishLandingCentre("tn_rameswaram", "Rameswaram",          "Ramanathapuram","TAMIL NADU", 9.2890,  79.3129),
        FishLandingCentre("tn_nagapattinam","Nagapattinam",       "Nagapattinam", "TAMIL NADU",  10.7672, 79.8449),
        FishLandingCentre("tn_cuddalore",  "Cuddalore",           "Cuddalore",    "TAMIL NADU",  11.7447, 79.7639),
        FishLandingCentre("tn_ennore",     "Ennore Jetty",        "Chennai",      "TAMIL NADU",  13.2100, 80.3300),
        FishLandingCentre("tn_mandapam",   "Mandapam Camp",       "Ramanathapuram","TAMIL NADU", 9.2776,  79.1432),

        // ── ANDHRA PRADESH ───────────────────────────────────────────────────
        FishLandingCentre("ap_vizag",      "Visakhapatnam Harbour","Visakhapatnam","ANDHRA PRADESH",17.6868,83.2185),
        FishLandingCentre("ap_kakinada",   "Kakinada",            "East Godavari","ANDHRA PRADESH",16.9402,82.2410),
        FishLandingCentre("ap_machili",    "Machilipatnam",       "Krishna",      "ANDHRA PRADESH",16.1875,81.1389),
        FishLandingCentre("ap_bhimili",    "Bhimili Beach",       "Visakhapatnam","ANDHRA PRADESH",17.8893,83.4535),
        FishLandingCentre("ap_nellore",    "Nellore Fish Market", "SPSR Nellore", "ANDHRA PRADESH",14.4426,79.9865),

        // ── ODISHA ───────────────────────────────────────────────────────────
        FishLandingCentre("od_puri",       "Puri Beach",          "Puri",         "ODISHA",      19.7984, 85.8313),
        FishLandingCentre("od_paradip",    "Paradip Harbour",     "Jagatsinghpur","ODISHA",      20.3167, 86.6111),
        FishLandingCentre("od_gopalpur",   "Gopalpur",            "Ganjam",       "ODISHA",      19.2631, 84.9062),
        FishLandingCentre("od_chatrapur",  "Chatrapur",           "Ganjam",       "ODISHA",      19.3540, 84.9930),

        // ── WEST BENGAL ──────────────────────────────────────────────────────
        FishLandingCentre("wb_digha",      "Digha",               "Purba Medinipur","WEST BENGAL",21.6260, 87.5080),
        FishLandingCentre("wb_sagar",      "Sagar Island",        "South 24 Parganas","WEST BENGAL",21.6510, 88.0810),

        // ── LAKSHADWEEP ──────────────────────────────────────────────────────
        FishLandingCentre("ld_kavaratti",  "Kavaratti Island",    "Lakshadweep",  "LAKSHADWEEP", 10.5626, 72.6369),
        FishLandingCentre("ld_minicoy",    "Minicoy Island",      "Lakshadweep",  "LAKSHADWEEP", 8.2764,  73.0350),

        // ── ANDAMAN & NICOBAR ─────────────────────────────────────────────────
        FishLandingCentre("an_portblair",  "Port Blair Jetty",    "South Andaman","ANDAMAN & NICOBAR",11.6234, 92.7265)
    )

    // ── Haversine distance (km) ────────────────────────────────────────────
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── Initial bearing from point 1 → point 2 (degrees, 0–360) ──────────
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dLon  = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(lat2r)
        val x = cos(lat1r) * sin(lat2r) - sin(lat1r) * cos(lat2r) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    // ── Cardinal + intercardinal direction from bearing ───────────────────
    fun bearingToDirection(deg: Double): String {
        val dirs = arrayOf("N","NNE","NE","ENE","E","ESE","SE","SSE",
                           "S","SSW","SW","WSW","W","WNW","NW","NNW")
        val idx = ((deg + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    // ── Find nearest FLC to a given position ──────────────────────────────
    fun findNearestFlc(lat: Double, lon: Double): FlcProximityResult {
        val nearest = ALL_FLCS.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
            ?: ALL_FLCS.first()
        val dist    = distanceKm(lat, lon, nearest.lat, nearest.lon)
        val bearing = bearingDeg(nearest.lat, nearest.lon, lat, lon)   // from FLC → hotspot
        return FlcProximityResult(
            flc       = nearest,
            distanceKm= dist,
            bearingDeg= bearing,
            direction = bearingToDirection(bearing)
        )
    }

    // ── bearing + distance from a specific FLC to a hotspot ───────────────
    fun fromFlcToHotspot(flc: FishLandingCentre, hotLat: Double, hotLon: Double): FlcProximityResult {
        val dist    = distanceKm(flc.lat, flc.lon, hotLat, hotLon)
        val bearing = bearingDeg(flc.lat, flc.lon, hotLat, hotLon)
        return FlcProximityResult(
            flc       = flc,
            distanceKm= dist,
            bearingDeg= bearing,
            direction = bearingToDirection(bearing)
        )
    }

    // ── Convert decimal degrees to DMS string ─────────────────────────────
    fun toDms(lat: Double, lon: Double): String {
        fun fmt(deg: Double, isLat: Boolean): String {
            val absD = abs(deg)
            val d    = absD.toInt()
            val mAll = (absD - d) * 60
            val m    = mAll.toInt()
            val s    = ((mAll - m) * 60).toInt()
            val hemi = if (isLat) (if (deg >= 0) "N" else "S") else (if (deg >= 0) "E" else "W")
            return "%d %02d %02d %s".format(d, m, s, hemi)
        }
        return "${fmt(lat, true)} ${fmt(lon, false)}"
    }

    // ── km → nautical miles ───────────────────────────────────────────────
    fun kmToNm(km: Double) = km / 1.852

    // ── Search FLCs by partial name / state / district ────────────────────
    fun search(query: String): List<FishLandingCentre> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return ALL_FLCS
        return ALL_FLCS.filter {
            it.name.lowercase().contains(q) ||
            it.state.lowercase().contains(q) ||
            it.district.lowercase().contains(q)
        }
    }
}
