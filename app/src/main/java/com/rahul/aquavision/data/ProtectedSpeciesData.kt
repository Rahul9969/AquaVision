package com.rahul.aquavision.data

/**
 * Protected species database based on:
 * - Wildlife Protection Act of India (Schedule I-IV)
 * - IUCN Red List status for Indian marine species
 * - CITES appendices
 *
 * Used to flag detected species that are protected/non-target,
 * contributing to conservation data collection.
 */
data class ProtectedSpeciesInfo(
    val commonName: String,
    val scientificName: String,
    val protectionStatus: String,    // e.g., "Schedule I", "IUCN Endangered"
    val iucnStatus: String,          // e.g., "EN", "VU", "CR", "NT", "LC"
    val conservationNote: String,
    val isDetectableByModel: Boolean // true if our YOLO model can detect this species
)

object ProtectedSpeciesRepository {

    /**
     * Comprehensive list of protected marine species in Indian waters.
     * Includes both species detectable by our model and additional protected species
     * from the Wildlife Protection Act.
     */
    val protectedSpecies: Map<String, ProtectedSpeciesInfo> = mapOf(

        // ============================================================
        // SPECIES DETECTABLE BY OUR YOLO MODEL (flagged during detection)
        // ============================================================

        "whale shark" to ProtectedSpeciesInfo(
            commonName = "Whale Shark",
            scientificName = "Rhincodon typus",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "EN",
            conservationNote = "India's largest fish. Fully protected since 2001. Catching, killing, or trading is a criminal offense with imprisonment up to 7 years.",
            isDetectableByModel = true
        ),

        "sawfish" to ProtectedSpeciesInfo(
            commonName = "Sawfish",
            scientificName = "Pristis spp.",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "CR",
            conservationNote = "Critically Endangered. All sawfish species are protected under Schedule I. Their rostrum (saw) is illegally traded. Report sightings to wildlife authorities.",
            isDetectableByModel = true
        ),

        "sea horse" to ProtectedSpeciesInfo(
            commonName = "Seahorse",
            scientificName = "Hippocampus spp.",
            protectionStatus = "Schedule I - Wildlife Protection Act, CITES Appendix II",
            iucnStatus = "VU",
            conservationNote = "All seahorse species are protected. Widely trafficked for traditional medicine. Possession and trade is illegal.",
            isDetectableByModel = true
        ),

        "napoleon wrasse" to ProtectedSpeciesInfo(
            commonName = "Napoleon Wrasse (Humphead)",
            scientificName = "Cheilinus undulatus",
            protectionStatus = "Schedule I - Wildlife Protection Act, CITES Appendix II",
            iucnStatus = "EN",
            conservationNote = "Endangered reef fish. Protected due to severe population decline from overfishing for live reef fish trade.",
            isDetectableByModel = true
        ),

        "dugong" to ProtectedSpeciesInfo(
            commonName = "Dugong (Sea Cow)",
            scientificName = "Dugong dugon",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "VU",
            conservationNote = "Marine mammal found in Gulf of Mannar and Andaman waters. Strictly protected. Accidental capture must be reported immediately.",
            isDetectableByModel = false
        ),

        // ============================================================
        // SPECIES FROM WILDLIFE PROTECTION ACT - SCHEDULE I
        // (Highest protection - strictly prohibited)
        // ============================================================

        "gangetic dolphin" to ProtectedSpeciesInfo(
            commonName = "Gangetic River Dolphin",
            scientificName = "Platanista gangetica",
            protectionStatus = "Schedule I - Wildlife Protection Act (National Aquatic Animal)",
            iucnStatus = "EN",
            conservationNote = "India's National Aquatic Animal. Found in Ganges-Brahmaputra river system. Bycatch is a major threat.",
            isDetectableByModel = false
        ),

        "irrawaddy dolphin" to ProtectedSpeciesInfo(
            commonName = "Irrawaddy Dolphin",
            scientificName = "Orcaella brevirostris",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "EN",
            conservationNote = "Found in Chilika Lake and coastal waters. Entanglement in fishing nets is the primary threat.",
            isDetectableByModel = false
        ),

        "olive ridley" to ProtectedSpeciesInfo(
            commonName = "Olive Ridley Sea Turtle",
            scientificName = "Lepidochelys olivacea",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "VU",
            conservationNote = "Mass nesting beaches in Odisha. Fishing near nesting beaches is prohibited during season. Use Turtle Excluder Devices (TEDs).",
            isDetectableByModel = false
        ),

        "green turtle" to ProtectedSpeciesInfo(
            commonName = "Green Sea Turtle",
            scientificName = "Chelonia mydas",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "EN",
            conservationNote = "Nests in Lakshadweep and Andaman-Nicobar Islands. All sea turtles are strictly protected in Indian waters.",
            isDetectableByModel = false
        ),

        "leatherback turtle" to ProtectedSpeciesInfo(
            commonName = "Leatherback Sea Turtle",
            scientificName = "Dermochelys coriacea",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "VU",
            conservationNote = "World's largest turtle. Nests in Andaman-Nicobar. Bycatch in gill nets and trawls is the primary threat.",
            isDetectableByModel = false
        ),

        "hawksbill turtle" to ProtectedSpeciesInfo(
            commonName = "Hawksbill Sea Turtle",
            scientificName = "Eretmochelys imbricata",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "CR",
            conservationNote = "Critically Endangered. Found in Indian coral reef areas. Shell trade is strictly prohibited internationally.",
            isDetectableByModel = false
        ),

        // ============================================================
        // SPECIES FROM WILDLIFE PROTECTION ACT - SCHEDULE II-IV
        // (Protected but lower schedules)
        // ============================================================

        "giant grouper" to ProtectedSpeciesInfo(
            commonName = "Giant Grouper",
            scientificName = "Epinephelus lanceolatus",
            protectionStatus = "Schedule II - Wildlife Protection Act",
            iucnStatus = "VU",
            conservationNote = "One of the largest bony fish in coral reefs. Slow-growing and vulnerable to overfishing. Protected in Indian waters.",
            isDetectableByModel = true
        ),

        "humphead parrotfish" to ProtectedSpeciesInfo(
            commonName = "Humphead Parrotfish",
            scientificName = "Bolbometopon muricatum",
            protectionStatus = "Schedule II - Wildlife Protection Act",
            iucnStatus = "VU",
            conservationNote = "Critical for coral reef health. Night-fishing of sleeping parrotfish is a major concern. Protected species.",
            isDetectableByModel = true
        ),

        "sea cucumber" to ProtectedSpeciesInfo(
            commonName = "Sea Cucumber",
            scientificName = "Holothuroidea spp.",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "EN",
            conservationNote = "All sea cucumber species are banned from harvesting in India since 2001. Widely poached for export to East Asian markets.",
            isDetectableByModel = false
        ),

        "pearl oyster" to ProtectedSpeciesInfo(
            commonName = "Indian Pearl Oyster",
            scientificName = "Pinctada fucata",
            protectionStatus = "Schedule IV - Wildlife Protection Act",
            iucnStatus = "NT",
            conservationNote = "Found in Gulf of Mannar. Harvesting regulated. Overharvesting threatens local populations.",
            isDetectableByModel = false
        ),

        "giant clam" to ProtectedSpeciesInfo(
            commonName = "Giant Clam",
            scientificName = "Tridacna spp.",
            protectionStatus = "Schedule I - Wildlife Protection Act, CITES Appendix II",
            iucnStatus = "VU",
            conservationNote = "Found in coral reef areas of Andaman-Nicobar and Lakshadweep. Collection and trade is strictly prohibited.",
            isDetectableByModel = false
        ),

        // ============================================================
        // COMMERCIALLY IMPORTANT BUT REGULATED SPECIES
        // (Not strictly protected but regulated/overfished)
        // ============================================================

        "hilsa" to ProtectedSpeciesInfo(
            commonName = "Hilsa Shad",
            scientificName = "Tenualosa ilisha",
            protectionStatus = "Regulated - Seasonal fishing ban",
            iucnStatus = "LC",
            conservationNote = "Not endangered but heavily regulated. Seasonal ban during breeding (Oct-Dec in some states). Follow local fishing advisories.",
            isDetectableByModel = true
        ),

        "pomfret" to ProtectedSpeciesInfo(
            commonName = "Silver Pomfret",
            scientificName = "Pampus argenteus",
            protectionStatus = "Regulated - Minimum size restrictions",
            iucnStatus = "NT",
            conservationNote = "Overfished in many regions. Minimum landing size enforced. Juvenile catch should be avoided.",
            isDetectableByModel = true
        ),

        "blue whale" to ProtectedSpeciesInfo(
            commonName = "Blue Whale",
            scientificName = "Balaenoptera musculus",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "EN",
            conservationNote = "Largest animal on Earth. All whales are protected in Indian waters. Ship strikes and fishing net entanglement are major threats.",
            isDetectableByModel = false
        ),

        "manta ray" to ProtectedSpeciesInfo(
            commonName = "Manta Ray",
            scientificName = "Mobula birostris",
            protectionStatus = "Schedule I - Wildlife Protection Act, CITES Appendix II",
            iucnStatus = "EN",
            conservationNote = "Protected since 2014 in India. Gill plates are illegally traded. If caught accidentally, must be released alive.",
            isDetectableByModel = false
        ),

        "guitar fish" to ProtectedSpeciesInfo(
            commonName = "Giant Guitarfish",
            scientificName = "Rhynchobatus djiddensis",
            protectionStatus = "Schedule I - Wildlife Protection Act",
            iucnStatus = "CR",
            conservationNote = "Critically Endangered. Resembles a shark-ray hybrid. Protected in 2022 amendment. Fins valued in international trade.",
            isDetectableByModel = false
        )
    )

    /**
     * Check if a species name matches any protected species.
     * Uses fuzzy matching to account for model label variations.
     */
    fun isProtected(speciesName: String): Boolean {
        val lower = speciesName.lowercase().trim()
        return protectedSpecies.any { (key, _) ->
            lower.contains(key) || key.contains(lower)
        }
    }

    /**
     * Get protection info for a detected species.
     * Returns null if not protected.
     */
    fun getProtectionInfo(speciesName: String): ProtectedSpeciesInfo? {
        val lower = speciesName.lowercase().trim()
        return protectedSpecies.entries.firstOrNull { (key, _) ->
            lower.contains(key) || key.contains(lower)
        }?.value
    }

    /**
     * Get all protected species that our YOLO model can detect.
     */
    fun getModelDetectableProtectedSpecies(): List<ProtectedSpeciesInfo> {
        return protectedSpecies.values.filter { it.isDetectableByModel }
    }

    /**
     * Get a user-friendly conservation message for display in alerts.
     */
    fun getConservationAlert(speciesName: String): String {
        val info = getProtectionInfo(speciesName) ?: return ""
        return buildString {
            appendLine("⚠️ ${info.commonName} (${info.scientificName})")
            appendLine()
            appendLine("Protection: ${info.protectionStatus}")
            appendLine("IUCN Status: ${getIUCNLabel(info.iucnStatus)}")
            appendLine()
            appendLine(info.conservationNote)
        }
    }

    private fun getIUCNLabel(code: String): String = when (code) {
        "CR" -> "Critically Endangered"
        "EN" -> "Endangered"
        "VU" -> "Vulnerable"
        "NT" -> "Near Threatened"
        "LC" -> "Least Concern"
        else -> code
    }
}
