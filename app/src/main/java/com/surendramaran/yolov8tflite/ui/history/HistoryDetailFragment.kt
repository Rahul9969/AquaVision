package com.surendramaran.yolov8tflite.ui.history

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.SpeciesRepository
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class HistoryDetailFragment : Fragment() {

    private var miniMap: MapView? = null

    // Same color palette as CameraFragment for consistency
    private val boxColors = listOf(
        Color.parseColor("#FF5722"), // Orange
        Color.parseColor("#2979FF"), // Blue
        Color.parseColor("#00C853"), // Green
        Color.parseColor("#FFD600"), // Yellow
        Color.parseColor("#AA00FF"), // Purple
        Color.parseColor("#E91E63"), // Pink
        Color.parseColor("#00BCD4"), // Cyan
        Color.parseColor("#3E2723")  // Brown
    )

    private val speciesColorMap = mutableMapOf<String, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        return inflater.inflate(R.layout.fragment_history_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val imagePathRaw = arguments?.getString("imagePath") ?: ""
        val timestamp = arguments?.getLong("timestamp") ?: 0L
        val titleRaw = arguments?.getString("fishCount") ?: ""
        val detailsRaw = arguments?.getString("details") ?: ""

        val placeName = arguments?.getString("placeName") ?: getString(R.string.unknown)
        val lat = arguments?.getFloat("lat")?.toDouble() ?: 0.0
        val lng = arguments?.getFloat("lng")?.toDouble() ?: 0.0

        val viewPager: ViewPager2 = view.findViewById(R.id.detailImagePager)
        val titleView: TextView = view.findViewById(R.id.detailTitle)
        val dateView: TextView = view.findViewById(R.id.detailDate)

        val placeNameView: TextView = view.findViewById(R.id.detailPlaceName)
        val coordsView: TextView = view.findViewById(R.id.detailCoords)
        val mapCard: View = view.findViewById(R.id.mapCard)
        val rawView: TextView = view.findViewById(R.id.detailRaw)

        miniMap = view.findViewById(R.id.miniMap)

        val imagePaths = imagePathRaw.split("|").filter { it.isNotEmpty() }
        val descriptionList = detailsRaw.split(";;;")
        viewPager.adapter = ImagePagerAdapter(imagePaths, descriptionList)

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        dateView.text = sdf.format(Date(timestamp))

        if (titleRaw.contains(",") || titleRaw.contains("%")) {
            titleView.text = "Analysis Report"
        } else {
            titleView.text = titleRaw.ifEmpty { "Detection Result" }
        }

        // --- NEW: Parse Data and Show Rich Graphics ---
        parseAndShowGraphics(view, titleRaw, detailsRaw)

        if (lat != 0.0 && lng != 0.0) {
            placeNameView.text = if (placeName != getString(R.string.unknown)) placeName else "Unknown Location"
            coordsView.text = String.format("%.4f, %.4f", lat, lng)

            mapCard.visibility = View.VISIBLE
            setupMiniMap(lat, lng)
        } else {
            mapCard.visibility = View.GONE
        }

        rawView.text = detailsRaw.replace(";;;", "\n\n")
    }

    private data class SpeciesCount(val name: String, val count: Int)

    private fun parseAndShowGraphics(view: View, rawTitle: String, rawDetails: String) {
        val stackedBarContainer: LinearLayout = view.findViewById(R.id.speciesStackedBar)
        val chipGroup: ChipGroup = view.findViewById(R.id.speciesLegendGroup)
        val biomassContainer: LinearLayout = view.findViewById(R.id.biomassListContainer)
        val biomassTitle: TextView = view.findViewById(R.id.tvBiomassTitle)
        val freshnessTitle: TextView = view.findViewById(R.id.tvFreshnessSummary)
        val freshnessProgress: LinearProgressIndicator = view.findViewById(R.id.freshnessProgress)

        // --- 0. Parse Freshness from Details ---
        if (rawDetails.contains("Freshness:")) {
            val matcher = Pattern.compile("Freshness: (\\d+)%").matcher(rawDetails)
            if (matcher.find()) {
                val freshPercent = matcher.group(1)?.toIntOrNull() ?: 0

                // We'll just show the main ratio string, e.g., "Freshness: 85%"
                // Finding the full string segment ending with ;;; or end of line
                val startIdx = rawDetails.indexOf("Freshness:")
                val endIdx = rawDetails.indexOf(";;;", startIdx)
                val displayStr = if (endIdx != -1) rawDetails.substring(startIdx, endIdx) else rawDetails.substring(startIdx)

                freshnessTitle.text = displayStr
                freshnessProgress.progress = freshPercent

                val color = if(freshPercent > 75) Color.parseColor("#4CAF50")
                else if(freshPercent > 40) Color.parseColor("#FF9800")
                else Color.parseColor("#F44336")

                freshnessTitle.setTextColor(color)
                freshnessProgress.setIndicatorColor(color)

                freshnessTitle.visibility = View.VISIBLE
                freshnessProgress.visibility = View.VISIBLE
            }
        }

        if (rawTitle.isEmpty()) return

        // Parse String: "Rohu 85%, Catla 90%, Eyes: 2"
        val parts = rawTitle.split(",").map { it.trim() }
        val speciesList = mutableListOf<String>()

        for (part in parts) {
            if (part.startsWith("Eyes:", ignoreCase = true)) continue

            // Remove confidence percentage if present (e.g., "Rohu 85%")
            var name = part
            val lastSpace = part.lastIndexOf(' ')
            if (lastSpace != -1) {
                val potentialConf = part.substring(lastSpace + 1)
                if (potentialConf.contains("%")) {
                    name = part.substring(0, lastSpace).trim()
                }
            }
            speciesList.add(name)
        }

        if (speciesList.isEmpty()) {
            view.findViewById<View>(R.id.cardSpeciesBar).visibility = View.GONE
            view.findViewById<View>(R.id.tvSpeciesRatioTitle).visibility = View.GONE
            biomassTitle.visibility = View.GONE
            return
        }

        // Group Counts
        val grouped = speciesList.groupingBy { it }.eachCount()
        val totalFish = speciesList.size

        // --- 1. Species Ratio (Stacked Bar) ---
        stackedBarContainer.removeAllViews()
        chipGroup.removeAllViews()

        var colorIndex = 0
        grouped.entries.sortedByDescending { it.value }.forEach { (species, count) ->
            val color = boxColors[colorIndex % boxColors.size]
            speciesColorMap[species] = color
            colorIndex++

            // Add Bar Segment
            val segment = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            params.weight = count.toFloat()
            segment.layoutParams = params
            segment.setBackgroundColor(color)
            stackedBarContainer.addView(segment)

            // Add Legend Chip
            val chip = Chip(requireContext())
            val ratio = (count.toFloat() / totalFish) * 100
            chip.text = "$species: $count (${ratio.toInt()}%)"
            chip.chipBackgroundColor = ColorStateList.valueOf(color)
            chip.setTextColor(Color.WHITE)
            chip.isClickable = false
            chipGroup.addView(chip)
        }

        // --- 2. Biomass (Vertical List) ---
        biomassContainer.removeAllViews()
        var grandTotalWeight = 0.0
        var grandTotalVolume = 0.0
        val speciesStats = mutableListOf<Triple<String, Double, Double>>() // Name, Weight, Volume

        grouped.forEach { (species, count) ->
            val info = SpeciesRepository.getSpeciesInfo(species)
            val totalWeight = count * info.avgWeight
            val totalVolume = count * info.avgVolume
            grandTotalWeight += totalWeight
            grandTotalVolume += totalVolume
            speciesStats.add(Triple(species, totalWeight, totalVolume))
        }

        val totalKg = grandTotalWeight / 1000.0
        val totalLiters = grandTotalVolume / 1000.0
        biomassTitle.text = "Est. Biomass (Total: ${String.format("%.2f", totalKg)} kg | ${String.format("%.2f", totalLiters)} L)"

        speciesStats.sortedByDescending { it.second }.forEach { (species, weight, volume) ->
            val color = speciesColorMap[species] ?: Color.GRAY

            // Row Layout
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
            }

            // Text
            val weightKg = weight / 1000.0
            val volumeL = volume / 1000.0
            val infoText = TextView(requireContext()).apply {
                text = "$species: ${String.format("%.1f", weightKg)} kg  |  ${String.format("%.1f", volumeL)} L"
                textSize = 14f
                setTextColor(Color.parseColor("#424242"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
            }

            // Progress Bar
            val progressIndicator = LinearProgressIndicator(requireContext()).apply {
                trackCornerRadius = (4 * resources.displayMetrics.density).toInt()
                trackColor = Color.parseColor("#EEEEEE")
                setIndicatorColor(color)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8 * resources.displayMetrics.density).toInt())

                val progressVal = if(grandTotalWeight > 0) ((weight / grandTotalWeight) * 100).toInt() else 0
                progress = progressVal
            }

            rowLayout.addView(infoText)
            rowLayout.addView(progressIndicator)
            biomassContainer.addView(rowLayout)
        }
    }

    private fun setupMiniMap(lat: Double, lng: Double) {
        miniMap?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            val point = GeoPoint(lat, lng)
            controller.setCenter(point)

            val marker = Marker(this)
            marker.position = point
            marker.icon = createSmallDot(Color.RED)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            overlays.add(marker)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    private fun createSmallDot(color: Int): BitmapDrawable {
        val size = 30
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = color; style = Paint.Style.FILL; isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() { super.onResume(); miniMap?.onResume() }
    override fun onPause() { super.onPause(); miniMap?.onPause() }

    class ImagePagerAdapter(private val paths: List<String>, private val descriptions: List<String>) : RecyclerView.Adapter<ImagePagerAdapter.ImgViewHolder>() {
        class ImgViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val img: ImageView = itemView.findViewById(R.id.image)
            val desc: TextView = itemView.findViewById(R.id.tvDescription)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image_detail, parent, false)
            return ImgViewHolder(view)
        }
        override fun onBindViewHolder(holder: ImgViewHolder, position: Int) {
            val file = File(paths[position])
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                holder.img.setImageBitmap(bmp)
            } else {
                holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            val detailText = descriptions.getOrElse(position) { "" }
            if (detailText.isNotEmpty()) {
                holder.desc.text = detailText
                holder.desc.visibility = View.VISIBLE
            } else {
                holder.desc.visibility = View.GONE
            }
        }
        override fun getItemCount() = paths.size
    }
}