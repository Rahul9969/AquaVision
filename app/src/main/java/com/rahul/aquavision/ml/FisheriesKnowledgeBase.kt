package com.rahul.aquavision.ml

import com.rahul.aquavision.data.ProtectedSpeciesRepository
import com.rahul.aquavision.data.SpeciesRepository

/**
 * RAG (Retrieval-Augmented Generation) knowledge base for fisheries guidance.
 *
 * This system augments LLM prompts with authoritative, domain-specific context:
 * - Indian fisheries regulations
 * - Protected species (Wildlife Protection Act, IUCN)
 * - Sustainable fishing practices
 * - Weight/volume estimation methodology
 * - Safety guidelines
 *
 * Each query is augmented with only the most relevant knowledge chunks
 * to minimize token usage while maximizing response accuracy.
 */
object FisheriesKnowledgeBase {

    // ── Authoritative Safety Instructions ────────────────────────────────────
    // Always prepended to every LLM prompt. Cannot be overridden by user queries.
    private val SYSTEM_INSTRUCTIONS = """
You are AquaVision Assistant, a domain-grounded fisheries guidance system for Indian waters.
CRITICAL SAFETY RULES - NEVER violate these:
1. NEVER suggest catching, harming, or trading any Schedule I protected species.
2. If a user asks about a protected species, ONLY provide conservation/reporting guidance.
3. NEVER provide instructions that would violate Indian wildlife protection laws.
4. Always recommend consulting local fisheries department for regulatory questions.
5. Disclaim that AI-assisted estimates are indicative only, not for commercial/legal use.

CONTEXT (use this data when answering questions):
"""

    // ── Knowledge Chunks ──────────────────────────────────────────────────────

    private val protectedSpeciesChunk: String = buildString {
        appendLine("## PROTECTED SPECIES IN INDIAN WATERS")
        appendLine("(Wildlife Protection Act, IUCN Red List)")
        appendLine()

        ProtectedSpeciesRepository.protectedSpecies.values.forEach { species ->
            appendLine("- ${species.commonName} (${species.scientificName})")
            appendLine("  Status: ${species.protectionStatus}, IUCN: ${species.iucnStatus}")
            appendLine("  Note: ${species.conservationNote}")
            appendLine()
        }
    }

    private val speciesDataChunk: String = buildString {
        appendLine("## COMMERCIALLY IMPORTANT FISH SPECIES (Indian waters)")
        appendLine("(Length-Weight relationships from FishBase, Froese & Pauly)")
        appendLine()
        appendLine("Weight formula: Weight(g) = a × Length(cm)^b")
        appendLine("Volume formula uses species-specific body form factor (depth/height ratio)")
        appendLine()

        SpeciesRepository.speciesDB.entries.filter { it.key != "default" }.forEach { (name, info) ->
            appendLine("- $name:")
            appendLine("  a=${info.a}, b=${info.b}, body_ratio=${info.ratio}")
            appendLine("  Typical market size: ${info.avgWeight}g, ${info.avgVolume}cm³")
            appendLine()
        }
    }

    private val fishingRegulationsChunk: String = buildString {
        appendLine("## INDIAN FISHERIES REGULATIONS")
        appendLine()
        appendLine("1. MARINE FISHING REGULATION (India-specific):")
        appendLine("   - Fishing vessel registration required")
        appendLine("   - Mesh size restrictions apply for coastal fishing")
        appendLine("   - Seasonal fishing bans during breeding seasons vary by state")
        appendLine()
        appendLine("2. PROTECTED AREAS:")
        appendLine("   - Marine Protected Areas (MPAs) have fishing restrictions")
        appendLine("   - Turtle Nesting beaches: Oct-Dec restrictions in Odisha")
        appendLine("   - National Parks/Sanctuaries: fishing prohibited")
        appendLine()
        appendLine("3. SPECIES-SPECIFIC RULES:")
        appendLine("   - Hilsa: seasonal ban Oct-Dec in some states")
        appendLine("   - Pomfret: minimum landing size restrictions")
        appendLine("   - Sea turtles: Turtle Excluder Devices (TEDs) required in trawls")
        appendLine("   - All Schedule I species: complete protection, no harvest")
        appendLine()
        appendLine("4. REPORTING VIOLATIONS:")
        appendLine("   - Contact nearest Fisheries Department office")
        appendLine("   - State-wise helplines available")
        appendLine("   - Wildlife Crime Control Bureau: report illegal wildlife trade")
    }

    private val sustainableFishingChunk: String = buildString {
        appendLine("## SUSTAINABLE FISHING PRACTICES")
        appendLine()
        appendLine("1. SIZE SELECTION:")
        appendLine("   - Land only commercially viable sizes")
        appendLine("   - Return juveniles to water (they are under-sized)")
        appendLine()
        appendLine("2. GEAR SELECTION:")
        appendLine("   - Use appropriate mesh sizes to allow juveniles to escape")
        appendLine("   - TEDs (Turtle Excluder Devices) in trawl nets are mandatory")
        appendLine("   - Avoid destructive gears near coral reefs and seagrass beds")
        appendLine()
        appendLine("3. BYCATCH REDUCTION:")
        appendLine("   - Monitor and minimize bycatch of non-target species")
        appendLine("   - Report protected species bycatch to authorities")
        appendLine()
        appendLine("4. CATCH DOCUMENTATION:")
        appendLine("   - Record all species caught (including discarded)")
        appendLine("   - App can assist with species identification")
        appendLine("   - Weight/volume estimates from app are for guidance only")
    }

    private val appCapabilitiesChunk: String = buildString {
        appendLine("## AquaVision App Capabilities")
        appendLine()
        appendLine("- Fish species identification via camera")
        appendLine("- AR-based 3D measurement for volume/weight estimation")
        appendLine("- Weight formula: uses species-specific length-weight coefficients (FishBase data)")
        appendLine("- Volume formula: disc integration using species body form factors")
        appendLine("- Freshness scoring: color analysis of gills and eyes")
        appendLine("- Protected species alerts: Wildlife Protection Act database included")
        appendLine("- Catch logging with GPS location")
        appendLine()
        appendLine("LIMITATIONS:")
        appendLine("- Weight/volume estimates are indicative (±15% typical error)")
        appendLine("- Species identification accuracy depends on image quality")
        appendLine("- Not a substitute for regulatory compliance or official inspections")
    }

    // ── Keyword-Based Retrieval ──────────────────────────────────────────────

    private data class KnowledgeChunk(val keywords: List<String>, val content: String)

    private val chunks = listOf(
        KnowledgeChunk(
            listOf("protect", "endanger", "endangered", "schedule", "illegal", "iucn", "whale shark", "sawfish", "sea horse", "dugong", "dolphin", "turtle", "wrasse", "crab", "clam", "cucumber", "pearl"),
            protectedSpeciesChunk
        ),
        KnowledgeChunk(
            listOf("weight", "volume", "estimate", "measure", "length", "biomass", "size", "market", "grams", "kg", "cm", "coefficient", "body", "form factor"),
            speciesDataChunk
        ),
        KnowledgeChunk(
            listOf("regulation", "law", "rule", "act", "license", "permit", "vessel", "ban", "restriction", "minimum size", "mesh", "trawling", "traffic", "violation"),
            fishingRegulationsChunk
        ),
        KnowledgeChunk(
            listOf("sustainable", "bycatch", "juvenile", "overfish", "ecosystem", "reef", "ted", "excluder", "selectivity", "mesh", "escape"),
            sustainableFishingChunk
        ),
        KnowledgeChunk(
            listOf("app", "capability", "feature", "freshness", "identify", "species", "scan", "measure", "ar", "camera", "gps", "location", "how", "use"),
            appCapabilitiesChunk
        )
    )

    /**
     * Retrieves knowledge chunks relevant to the user query.
     * Uses simple keyword matching for on-device performance.
     */
    fun retrieveRelevantContext(query: String): String {
        val lowerQuery = query.lowercase()
        val relevantChunks = chunks.filter { chunk ->
            chunk.keywords.any { keyword -> lowerQuery.contains(keyword) }
        }
        return if (relevantChunks.isEmpty()) {
            // Always include at least basic safety instructions
            appCapabilitiesChunk
        } else {
            relevantChunks.joinToString("\n") { it.content }
        }
    }

    /**
     * Builds the full prompt with RAG grounding.
     * Prepends system instructions + retrieved knowledge to user query.
     */
    fun buildGroundedPrompt(userQuery: String): String {
        val relevantContext = retrieveRelevantContext(userQuery)
        return buildString {
            appendLine(SYSTEM_INSTRUCTIONS.trim())
            appendLine()
            appendLine(relevantContext)
            appendLine()
            appendLine("USER QUERY: $userQuery")
            appendLine()
            append("RESPONSE:")
        }
    }

    /**
     * Format the detected species context for grounding in conversation.
     */
    fun buildSpeciesContext(speciesName: String): String {
        val speciesInfo = SpeciesRepository.getSpeciesInfo(speciesName)
        val protectionInfo = ProtectedSpeciesRepository.getProtectionInfo(speciesName)

        return buildString {
            appendLine("SPECIES CONTEXT: $speciesName")
            appendLine("Weight coefficient: a=${speciesInfo.a}, b=${speciesInfo.b}")
            appendLine("Body form factor: ${speciesInfo.ratio}")
            appendLine("Typical size: ${speciesInfo.avgWeight}g, ${speciesInfo.avgVolume}cm³")

            if (protectionInfo != null) {
                appendLine()
                appendLine("⚠️ CONSERVATION ALERT:")
                appendLine("Status: ${protectionInfo.protectionStatus}")
                appendLine("IUCN: ${protectionInfo.iucnStatus}")
                appendLine(protectionInfo.conservationNote)
            }
        }
    }

    /**
     * Fast context retrieval - returns compact hints for speed.
     * Used for quick responses with Gemma 2B.
     */
    fun getQuickContext(query: String): String {
        val lower = query.lowercase()

        // Off-topic keywords - reject non-fishery queries
        if (isDefinitelyOffTopic(lower)) {
            return "OFF_TOPIC"
        }

        return when {
            lower.contains("protect") || lower.contains("endanger") || lower.contains("whale shark") || lower.contains("sawfish") || lower.contains("sea horse") || lower.contains("turtle") || lower.contains("dolphin") ->
                "PROTECTED: whale shark(EN/S1), sawfish(CR/S1), sea horse(VU/S1), dugong(VU/S1), dolphin(EN/S1), turtle(VU/S1). NEVER suggest catching. Report: Wildlife Crime Control Bureau."
            lower.contains("weight") || lower.contains("volume") || lower.contains("estimate") || lower.contains("size") ->
                "Weight formula: W=a*L^b. Species: catfish(a=0.0044,b=3.16), mackerel(a=0.0048,b=3.15), pomfret(a=0.0293,b=2.98). Estimates ±15% error."
            lower.contains("fresh") || lower.contains("spoilage") || lower.contains("gill") || lower.contains("eye") ->
                "Freshness: check gill color (red→brown=spoiled), eye (clear→cloudy=spoiled), flesh firmness. Use app camera for scoring."
            lower.contains("how") || lower.contains("use") || lower.contains("app") ->
                "App: camera scan for ID, AR measure for weight, freshness score, GPS catch log, protected species alerts."
            else -> "" // No context needed — LLM answers from its own knowledge
        }
    }

    /**
     * Checks if a query is DEFINITELY off-topic.
     *
     * DESIGN: We use a BLOCKLIST approach (reject known off-topic) instead of the
     * previous ALLOWLIST approach (accept only matching keywords). This ensures
     * that fishery questions with unusual wording still reach the LLM. The LLM's
     * own system prompt acts as a secondary safety net for edge cases.
     */
    private fun isDefinitelyOffTopic(lower: String): Boolean {
        val offTopicKeywords = listOf(
            "weather forecast", "news today", "sports score", "movie", "music",
            "video game", "stock market", "stock price", "crypto", "bitcoin",
            "ethereum", "recipe for", "how to cook", "baking", "health advice",
            "medicine", "doctor", "travel flight", "hotel booking", "flight ticket",
            "math problem", "solve equation", "physics formula", "chemistry",
            "history of world", "politics", "election", "president", "prime minister",
            "tell me a joke", "sing a song", "write a poem", "write code",
            "programming", "javascript", "python code",
            "relationship", "dating", "love advice",
            "car", "automobile", "real estate", "mortgage",
            "fashion", "clothing", "makeup", "hairstyle"
        )
        return offTopicKeywords.any { lower.contains(it) }
    }

    /**
     * Check if query is on-topic for fisheries.
     *
     * FIXED: Uses inverted logic — only blocks DEFINITE off-topic queries.
     * Everything else is allowed through to the LLM, which has its own system
     * prompt to handle edge cases. This prevents blocking legitimate fishery
     * questions that don't match a narrow keyword list.
     *
     * Greetings and ambiguous queries are always allowed.
     */
    fun isOnTopic(query: String): Boolean {
        val lower = query.lowercase().trim()

        // Always allow greetings and very short queries
        if (lower.length < 4) return true
        val greetings = listOf("hi", "hey", "hello", "hola", "namaste", "thanks", "thank you", "ok", "yes", "no", "bye", "good morning", "good evening", "good night")
        if (greetings.any { lower.startsWith(it) }) return true

        // Allow any query that contains a fishery-related keyword
        val onTopicKeywords = listOf(
            // General fishery terms
            "fish", "catch", "fisher", "marine", "aqua", "water", "ocean", "sea",
            "river", "lake", "pond", "creek", "estuary", "coast", "shore", "beach",
            "boat", "net", "hook", "bait", "lure", "rod", "reel", "trawl", "line",
            "species", "breed", "spawn", "fry", "fingerling", "juvenile", "adult",
            "habitat", "depth", "current", "tide", "monsoon", "season",
            // Indian species names
            "rohu", "catla", "hilsa", "pomfret", "mackerel", "sardine", "anchovy",
            "tuna", "seer", "kingfish", "barramundi", "murrel", "snakehead",
            "tilapia", "carp", "mrigal", "pabda", "magur", "singi", "tengra",
            "bombay duck", "ribbon", "sole", "flounder", "grouper", "snapper",
            "perch", "surmai", "rawas", "bangda", "paplet", "vaam", "eel",
            "prawn", "shrimp", "lobster", "crab", "squid", "octopus", "mussel",
            "clam", "oyster", "scallop",
            // Measurement & science
            "volume", "weight", "length", "measure", "estimate", "biomass",
            "size", "grams", "kg", "cm", "inch",
            // Protection & regulation
            "protect", "endanger", "turtle", "dolphin", "whale", "dugong",
            "regulation", "law", "ban", "permit", "license",
            "sustainable", "conservation", "bycatch", "overfishing",
            // Freshness & quality
            "fresh", "spoil", "gill", "eye", "smell", "texture", "quality",
            "ice", "cold chain", "storage", "frozen",
            // App features
            "aquavision", "app", "scan", "detect", "identif", "camera", "ar",
            "3d", "measure", "gps", "log", "history",
            // Food & nutrition (fish-related)
            "omega", "protein", "nutrition", "fillet", "clean", "gut", "scale",
            "market", "price", "sell", "buy", "trade", "export", "import",
            // Aquaculture
            "aquaculture", "hatchery", "farm", "culture", "feed", "pellet",
            "aeration", "dissolved oxygen", "ph", "ammonia", "stocking"
        )

        if (onTopicKeywords.any { lower.contains(it) }) return true

        // If no keyword matched, still allow it — the LLM's system prompt
        // will refuse truly off-topic queries. Only block definite off-topic.
        return !isDefinitelyOffTopic(lower)
    }
}