package com.rahul.aquavision.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rahul.aquavision.R
import com.rahul.aquavision.data.DatabaseHelper
import com.rahul.aquavision.data.HistoryItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val historyList: List<HistoryItem>,
    private val onItemClick: (HistoryItem, View) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.historyImage)
        val date: TextView = view.findViewById(R.id.historyDate)
        val location: TextView = view.findViewById(R.id.historyLocation)
        val counts: TextView = view.findViewById(R.id.historyCounts)
        val details: TextView = view.findViewById(R.id.historyDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = historyList[position]

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        holder.date.text = sdf.format(Date(item.timestamp))

        holder.location.text = String.format("Lat: %.4f, Lng: %.4f", item.lat, item.lng)
        holder.location.visibility = View.VISIBLE

        // Format title cleanly based on record type
        holder.counts.text = when (item.type) {
            DatabaseHelper.TYPE_DETECTION -> formatDetectionTitle(item.title)
            DatabaseHelper.TYPE_FRESHNESS -> item.title  // Already clean (e.g. "FRESH (79%)")
            DatabaseHelper.TYPE_VOLUME    -> item.title
            else -> item.title
        }

        // Format details cleanly (strip raw confidence arrays and raw percentage lists)
        holder.details.text = formatDetails(item.details, item.type)

        // Handle multiple image paths separated by "|"
        val firstPath = item.imagePath.split("|").firstOrNull() ?: ""
        val imgFile = File(firstPath)
        if (imgFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            holder.image.setImageBitmap(bitmap)
        } else {
            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        holder.image.transitionName = item.imagePath
        holder.itemView.setOnClickListener { onItemClick(item, holder.image) }
    }

    /**
     * Converts raw title like:
     *   "Black Pomfret 83%, Black Pomfret 90%, Catla 77%, Eyes: 2"
     * into a clean grouped summary:
     *   "Black Pomfret x2 (avg 86%) • Catla x1 (77%)"
     */
    private fun formatDetectionTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return rawTitle

        val speciesConfMap = mutableMapOf<String, MutableList<Int>>()

        rawTitle.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            // Skip eyes meta token
            if (trimmed.startsWith("Eyes", ignoreCase = true)) return@forEach

            // New format: "SpeciesName 85%"
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace > 0) {
                val possibleConf = trimmed.substring(lastSpace + 1)
                if (possibleConf.endsWith("%")) {
                    val name = trimmed.substring(0, lastSpace).trim()
                    val conf = possibleConf.dropLast(1).toIntOrNull() ?: -1
                    if (name.isNotEmpty()) {
                        speciesConfMap.getOrPut(name) { mutableListOf() }.apply {
                            if (conf >= 0) add(conf)
                        }
                        return@forEach
                    }
                }
            }

            // Old format: "SpeciesName: 2"  — just record name without confidence
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val name = trimmed.substring(0, colonIdx).trim()
                val count = trimmed.substring(colonIdx + 1).trim().toIntOrNull() ?: 1
                repeat(count) { speciesConfMap.getOrPut(name) { mutableListOf() } }
                return@forEach
            }

            // Fallback: whole string is the name
            speciesConfMap.getOrPut(trimmed) { mutableListOf() }
        }

        if (speciesConfMap.isEmpty()) return rawTitle

        return speciesConfMap.entries
            .sortedByDescending { it.value.size }
            .joinToString(" • ") { (name, confs) ->
                val count = confs.size.coerceAtLeast(1)
                when {
                    confs.isEmpty() -> "$name x$count"
                    count == 1      -> "$name x1 (${confs[0]}%)"
                    else            -> "$name x$count (avg ${confs.average().toInt()}%)"
                }
            }
    }

    /**
     * Strips noisy raw arrays and raw percentage chains from details text.
     */
    private fun formatDetails(rawDetails: String, type: Int): String {
        if (rawDetails.isBlank()) return rawDetails
        return when (type) {
            DatabaseHelper.TYPE_DETECTION -> {
                // Remove raw confidence arrays: ", Conf: [0.90, 0.89, ...]"
                var cleaned = rawDetails
                    .replace(Regex(", Conf: \\[.*?]"), "")
                    .replace(Regex("Conf: \\[.*?]"), "")
                // Remove "Approx Freshness: X% (A/B);;;" prefix noise
                cleaned = cleaned.replace(Regex("Approx Freshness:.*?;;;"), "").trim()
                // Collapse multiple semicolons and clean up
                cleaned.replace(Regex(";{2,}"), " | ")
                    .replace(";;;", " | ")
                    .trimStart(' ', '|', ';')
                    .trim()
            }
            DatabaseHelper.TYPE_FRESHNESS -> {
                // Show only Part/Status/Confidence lines, skip raw score chains
                val parts = rawDetails.split(";;;").filter { it.isNotBlank() }
                val meaningful = parts.filter { seg ->
                    seg.contains("Status", ignoreCase = true) ||
                    seg.contains("Part", ignoreCase = true) ||
                    seg.contains("Confidence", ignoreCase = true)
                }
                meaningful.joinToString(" | ")
                    .replace("\n", " ")
                    .trim()
                    .ifEmpty { parts.firstOrNull()?.trim() ?: rawDetails }
            }
            else -> rawDetails
        }
    }

    override fun getItemCount() = historyList.size
}