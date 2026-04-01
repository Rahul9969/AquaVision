package com.surendramaran.yolov8tflite.data

/**
 * Holds biological constants for weight and volume estimation.
 * Formula for detailed calc: Weight (g) = a * Length(cm)^b
 * New: avgWeight (g) and avgVolume (cm3) for quick population estimation.
 */
data class SpeciesInfo(
    val a: Double,
    val b: Double,
    val ratio: Double,
    val avgWeight: Double, // Average weight in grams
    val avgVolume: Double  // Average volume in cm^3
)

object SpeciesRepository {
    // Database of species constants verified for Indian/Indo-Pacific species
    val speciesDB = mapOf(
        // 1. Catfish
        "catfish" to SpeciesInfo(0.0046, 3.19, 0.80, 500.0, 500.0),

        // 2. Catla
        "catla" to SpeciesInfo(0.0200, 3.00, 0.45, 2000.0, 1950.0),

        // 3. Hilsa
        "hilsa" to SpeciesInfo(0.0158, 2.92, 0.40, 800.0, 780.0),

        // 4. Mackerel
        "mackerel" to SpeciesInfo(0.0045, 3.22, 0.55, 200.0, 190.0),

        // 5. Mud Crab
        "mud crab" to SpeciesInfo(0.4300, 2.57, 0.30, 600.0, 550.0),

        // 6. Pomfret
        "pomfret" to SpeciesInfo(0.0324, 3.00, 0.15, 300.0, 290.0),

        // 7. Rohu
        "rohu" to SpeciesInfo(0.0130, 3.05, 0.55, 1500.0, 1450.0),

        // 8. Salmon
        "salmon" to SpeciesInfo(0.0100, 3.05, 0.55, 2500.0, 2400.0),

        // 9. Sardine
        "sardine" to SpeciesInfo(0.0093, 2.95, 0.50, 100.0, 95.0),

        // 10. Shrimp
        "shrimp" to SpeciesInfo(0.0039, 3.21, 0.80, 30.0, 28.0),

        // 11. Three Spotted Crab
        "three spotted crab" to SpeciesInfo(0.1340, 2.63, 0.30, 200.0, 190.0),
        "3 spotted crab" to SpeciesInfo(0.1340, 2.63, 0.30, 200.0, 190.0),

        // 12. Tuna
        "tuna" to SpeciesInfo(0.0145, 3.03, 0.60, 5000.0, 4800.0),

        // Fallback
        "default" to SpeciesInfo(0.0120, 3.00, 0.50, 500.0, 500.0)
    )

    fun getSpeciesInfo(speciesName: String): SpeciesInfo {
        for ((key, value) in speciesDB) {
            if (speciesName.contains(key, ignoreCase = true)) {
                return value
            }
        }
        return speciesDB["default"]!!
    }
}