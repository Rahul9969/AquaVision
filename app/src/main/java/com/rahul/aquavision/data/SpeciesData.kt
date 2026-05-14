package com.rahul.aquavision.data

/**
 * Holds biological constants for weight and volume estimation.
 *
 * Weight formula (FishBase / Froese & Pauly):
 *   Weight (g) = a × Length(cm)^b
 *
 * Volume formula (Solid of Revolution / Disc Integration):
 *   Vol(cm³) = Σ [π × ratio / 4 × h²] / pixelsPerCm³
 *   where h = fish body thickness at each cross-section (from segmentation mask)
 *   and ratio = depth-to-height ratio of fish cross-section (species-specific body form factor)
 *
 * Sources:
 *   - FishBase (Froese & Pauly, www.fishbase.org), median values for Indian waters
 *   - CMFRI Bulletin for Indo-Pacific coastal species
 *   - ICAR-CIFA species profiles for freshwater species
 */
data class SpeciesInfo(
    val a: Double,          // Length-weight coefficient (g/cm^b)
    val b: Double,          // Length-weight exponent (typically 2.9–3.3)
    val ratio: Double,      // Body form factor: depth-to-height at cross-section (0–1)
    val avgWeight: Double,  // Typical market-size weight (grams), used as lookup-table fallback
    val avgVolume: Double   // Typical market-size volume (cm³), used as lookup-table fallback
)

object SpeciesRepository {

    /**
     * Species constants calibrated for Indian/Indo-Pacific fisheries.
     *
     * ratio explanation:
     *   Fish lying on side in image: the mask gives lateral view (length × height).
     *   At each cross-section, the measured axis = height (h).
     *   The unmeasured depth axis = h × ratio.
     *   ratio = body_thickness / body_height at mid-body.
     *   Flat/compressed fish (pomfret) → small ratio (~0.22)
     *   Round/tubular fish (catfish, shrimp) → large ratio (~0.70-0.75)
     */
    val speciesDB = mapOf(

        // 1. Catfish (Clarias / Pangasius)
        // Elongated, subcircular cross-section. FishBase: a=0.0039–0.0054, b=3.10–3.22
        "catfish" to SpeciesInfo(a = 0.0044, b = 3.16, ratio = 0.72, avgWeight = 480.0, avgVolume = 470.0),

        // 2. Catla (Catla catla) — Indian major carp
        // Deep compressed body. FishBase India: a=0.0118–0.0218, b=2.97–3.04
        "catla" to SpeciesInfo(a = 0.0177, b = 3.01, ratio = 0.45, avgWeight = 1800.0, avgVolume = 1760.0),

        // 3. Hilsa / Hilsa shad (Tenualosa ilisha)
        // Strongly compressed laterally, silvery. FishBase: a=0.0079–0.0127, b=2.88–3.01
        "hilsa" to SpeciesInfo(a = 0.0126, b = 2.96, ratio = 0.35, avgWeight = 750.0, avgVolume = 730.0),

        // 4. Indian Mackerel (Rastrelliger kanagurta)
        // Fusiform, moderately compressed. FishBase: a=0.0041–0.0065, b=2.88–3.22
        "mackerel" to SpeciesInfo(a = 0.0048, b = 3.15, ratio = 0.50, avgWeight = 180.0, avgVolume = 175.0),

        // 5. Mud Crab (Scylla serrata)
        // Broad, flattened carapace. FishBase: a=0.32–0.45, b=2.55–2.67
        "mud crab" to SpeciesInfo(a = 0.3820, b = 2.61, ratio = 0.32, avgWeight = 550.0, avgVolume = 520.0),

        // 6. Pomfret (Black: Parastromateus niger / Silver: Pampus argenteus)
        // Highly compressed (disc-like). FishBase: a=0.0245–0.0365, b=2.94–3.05
        // body_thickness ≈ 3.0–3.5 cm, body_height ≈ 14–16 cm → ratio ≈ 0.22
        "pomfret" to SpeciesInfo(a = 0.0293, b = 2.98, ratio = 0.22, avgWeight = 350.0, avgVolume = 335.0),

        // 7. Rohu (Labeo rohita) — Indian major carp
        // Moderately compressed. FishBase India: a=0.0070–0.0150, b=2.99–3.11
        "rohu" to SpeciesInfo(a = 0.0096, b = 3.06, ratio = 0.52, avgWeight = 1200.0, avgVolume = 1170.0),

        // 8. Atlantic/Pacific Salmon (Salmo/Oncorhynchus spp.)
        // Fusiform body, subcircular. FishBase: a=0.0082–0.0105, b=2.98–3.06
        "salmon" to SpeciesInfo(a = 0.0093, b = 3.02, ratio = 0.55, avgWeight = 3500.0, avgVolume = 3400.0),

        // 9. Indian/Oil Sardine (Sardinella longiceps / S. gibbosa)
        // Small, slightly compressed. FishBase: a=0.0067–0.0100, b=2.90–3.00
        "sardine" to SpeciesInfo(a = 0.0083, b = 2.98, ratio = 0.48, avgWeight = 90.0, avgVolume = 87.0),

        // 10. Shrimp / Prawn (Penaeus monodon / P. indicus)
        // Subcircular in cross-section, curved body. FishBase: a=0.0022–0.0039, b=2.95–3.21
        "shrimp" to SpeciesInfo(a = 0.0036, b = 3.17, ratio = 0.75, avgWeight = 25.0, avgVolume = 24.0),

        // 11. Three-spot / Blue Swimming Crab (Portunus sanguinolentus / P. pelagicus)
        // Broad carapace, flatter than mud crab. FishBase: a=0.11–0.17, b=2.63–2.73
        "three spotted crab" to SpeciesInfo(a = 0.1510, b = 2.67, ratio = 0.32, avgWeight = 220.0, avgVolume = 210.0),
        "3 spotted crab"     to SpeciesInfo(a = 0.1510, b = 2.67, ratio = 0.32, avgWeight = 220.0, avgVolume = 210.0),

        // 12. Yellowfin / Skipjack Tuna (Thunnus albacares / Katsuwonus pelamis)
        // Robust fusiform, nearly circular cross-section. FishBase: a=0.0141–0.0185, b=2.91–3.03
        "tuna" to SpeciesInfo(a = 0.0162, b = 2.96, ratio = 0.58, avgWeight = 8000.0, avgVolume = 7700.0),

        // Fallback — generic fish (conservative midpoint)
        "default" to SpeciesInfo(a = 0.0120, b = 3.00, ratio = 0.48, avgWeight = 500.0, avgVolume = 490.0)
    )

    /**
     * Returns the best-matching species info for a given detection label.
     * Uses substring matching so "Black Pomfret" → "pomfret", etc.
     */
    fun getSpeciesInfo(speciesName: String): SpeciesInfo {
        for ((key, value) in speciesDB) {
            if (key == "default") continue
            if (speciesName.contains(key, ignoreCase = true)) {
                return value
            }
        }
        return speciesDB["default"]!!
    }
}