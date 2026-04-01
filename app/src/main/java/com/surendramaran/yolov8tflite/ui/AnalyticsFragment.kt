package com.surendramaran.yolov8tflite.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.HistoryItem
import com.surendramaran.yolov8tflite.databinding.FragmentAnalyticsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private lateinit var dbHelper: DatabaseHelper
    private var currentStartTime: Long = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())

        setupInteractivity()

        // Default to 7 days
        currentStartTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        loadAnalytics()
    }

    private fun setupInteractivity() {
        // Chip Group Listener
        binding.filterChipGroup.setOnCheckedChangeListener { _, checkedId ->
            currentStartTime = when (checkedId) {
                R.id.chip7Days -> System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                R.id.chip30Days -> System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                R.id.chipAllTime -> 0
                else -> System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            }
            loadAnalytics()
        }

        // Navigation Clicks
        binding.cardTotalCatch.setOnClickListener {
            findNavController().navigate(R.id.historyFragment)
        }

        binding.btnSeeAllRecent.setOnClickListener {
            findNavController().navigate(R.id.historyFragment)
        }

        binding.btnRefresh.setOnClickListener {
            loadAnalytics()
            Toast.makeText(context, "Data Refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAnalytics() {
        val stats = dbHelper.getAnalyticsStats(currentStartTime)

        // 1. Key Metrics
        binding.tvTotalCatch.text = stats.totalCatch.toString()
        val biomassKg = dbHelper.getTotalBiomass(currentStartTime)
        binding.tvBiomass.text = getString(R.string.weight_value, biomassKg)

        // NEW: Show Total Eyes with resource string
        if (stats.totalEyes > 0) {
            binding.tvTotalEyes.text = getString(R.string.total_eyes_format, stats.totalEyes)
            binding.tvTotalEyes.visibility = View.VISIBLE
        } else {
            binding.tvTotalEyes.visibility = View.GONE
        }

        // Freshness Index
        val totalFreshnessChecks = stats.freshCount + stats.spoiledCount
        if (totalFreshnessChecks > 0) {
            val freshRate = (stats.freshCount.toFloat() / totalFreshnessChecks.toFloat()) * 100
            binding.tvFreshRate.text = "${freshRate.toInt()}%"
            binding.freshnessProgress.progress = freshRate.toInt()

            val colorRes = if(freshRate > 75) R.color.overlay_green else if(freshRate > 50) R.color.orange else R.color.overlay_red
            binding.freshnessProgress.setIndicatorColor(ContextCompat.getColor(requireContext(), colorRes))
        } else {
            binding.tvFreshRate.text = "--"
            binding.freshnessProgress.progress = 0
        }

        // 2. Upload Status (Updated UI - Summary)
        setupUploadStatus()

        // 3. Size Distribution (Smaller Sliders)
        setupSizeDistribution()

        // 4. Hourly Activity Heatmap
        val hourlyData = dbHelper.getHourlyActivity(currentStartTime)
        setupHourlyChart(hourlyData)

        // 5. Weekly Activity Chart
        val weeklyData = dbHelper.getWeeklyActivity()
        setupBarChart(weeklyData)

        // 6. Recent Activity List
        setupRecentActivity()

        // 7. Top Locations
        setupTopLocations()

        // 8. Species Breakdown
        binding.speciesListContainer.removeAllViews()
        val sortedSpecies = stats.speciesBreakdown.toList().sortedByDescending { it.second }

        if (sortedSpecies.isNotEmpty()) {
            val maxCount = sortedSpecies.first().second.toFloat()

            for ((name, count) in sortedSpecies) {
                val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_analytics_species, binding.speciesListContainer, false)

                val tvName = row.findViewById<TextView>(R.id.tvSpeciesName)
                val tvCount = row.findViewById<TextView>(R.id.tvSpeciesCount)
                val progressBar = row.findViewById<LinearProgressIndicator>(R.id.speciesProgress)

                tvName.text = name
                tvCount.text = count.toString()

                val progress = ((count / maxCount) * 100).toInt()
                progressBar.progress = progress

                binding.speciesListContainer.addView(row)
            }
        } else {
            addEmptyState(binding.speciesListContainer)
        }
    }

    private fun setupUploadStatus() {
        binding.uploadStatusContainer.removeAllViews()
        val unsyncedLogs = dbHelper.getUnsyncedLogs()

        val summaryView = LinearLayout(requireContext())
        summaryView.orientation = LinearLayout.HORIZONTAL
        summaryView.gravity = Gravity.CENTER_VERTICAL
        summaryView.setPadding(16, 16, 16, 16)

        val icon = ImageView(requireContext())
        val iconSize = (24 * resources.displayMetrics.density).toInt()
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.marginEnd = (12 * resources.displayMetrics.density).toInt()
        icon.layoutParams = iconParams

        val textLayout = LinearLayout(requireContext())
        textLayout.orientation = LinearLayout.VERTICAL

        val title = TextView(requireContext())
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.BLACK)

        val subtitle = TextView(requireContext())
        subtitle.textSize = 12f
        subtitle.setTextColor(Color.GRAY)

        textLayout.addView(title)
        textLayout.addView(subtitle)

        if (unsyncedLogs.isEmpty()) {
            icon.setImageResource(android.R.drawable.stat_sys_upload_done)
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary))
            title.text = "All Data Synced"
            subtitle.text = "Your records are safe in the cloud."
        } else {
            icon.setImageResource(android.R.drawable.stat_notify_sync)
            icon.setColorFilter(Color.parseColor("#FFA000")) // Amber
            title.text = "${unsyncedLogs.size} Items Pending"
            subtitle.text = "Waiting for network to sync."
        }

        summaryView.addView(icon)
        summaryView.addView(textLayout)

        binding.uploadStatusContainer.addView(summaryView)
    }

    private fun setupSizeDistribution() {
        binding.sizeDistContainer.removeAllViews()
        val (small, medium, large) = dbHelper.getSizeDistribution(currentStartTime)
        val total = small + medium + large

        if (total == 0) {
            addEmptyState(binding.sizeDistContainer)
            return
        }

        fun addRow(label: String, count: Int, color: Int, description: String) {
            val row = LinearLayout(requireContext())
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 12, 0, 12)
            row.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chip_transparent)
            row.background.setTint(Color.parseColor("#FAFAFA"))

            val labelTv = TextView(requireContext())
            labelTv.text = label
            labelTv.textSize = 14f
            labelTv.setTypeface(null, Typeface.BOLD)
            // Increased label weight to give it more space, reducing bar space
            labelTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            labelTv.setTextColor(Color.BLACK)

            val progress = LinearProgressIndicator(requireContext())
            val progressHeight = (20 * resources.displayMetrics.density).toInt() // Back to slim
            // Reduced progress bar weight to 1.5f (was 3f) to make it shorter
            val params = LinearLayout.LayoutParams(0, progressHeight, 2.5f)
            params.gravity = Gravity.CENTER_VERTICAL
            progress.layoutParams = params
            progress.trackCornerRadius = (progressHeight / 2)
            progress.setIndicatorColor(color)
            progress.trackColor = Color.parseColor("#E0E0E0")
            progress.progress = if (total > 0) ((count.toFloat() / total) * 100).toInt() else 0

            val countTv = TextView(requireContext())
            countTv.text = "$count"
            countTv.textSize = 14f
            countTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
            countTv.gravity = Gravity.END
            countTv.setTextColor(Color.GRAY)

            row.addView(labelTv)
            row.addView(progress)
            row.addView(countTv)

            row.setOnClickListener {
                Toast.makeText(requireContext(), "$label: $description", Toast.LENGTH_SHORT).show()
            }

            binding.sizeDistContainer.addView(row)
        }

        addRow("Small", small, Color.parseColor("#42A5F5"), "< 500g")
        addRow("Medium", medium, Color.parseColor("#66BB6A"), "500g - 2kg")
        addRow("Large", large, Color.parseColor("#FFA726"), "> 2kg")
    }

    private fun setupHourlyChart(data: Map<Int, Int>) {
        binding.hourlyChartContainer.removeAllViews()
        val maxVal = data.values.maxOrNull()?.toFloat() ?: 0f

        binding.tvHourlyTooltip.text = "Tap bars for details"

        if (maxVal == 0f) {
            addEmptyState(binding.hourlyChartContainer)
            return
        }

        for (hour in 0..23) {
            val count = data[hour] ?: 0
            val barView = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = 2
            barView.layoutParams = params

            val intensity = if (count > 0) (count / maxVal).coerceAtLeast(0.1f) else 0.05f
            barView.alpha = intensity
            barView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))

            barView.setOnClickListener {
                val timeLabel = String.format("%02d:00 - %02d:00", hour, hour + 1)
                binding.tvHourlyTooltip.text = "$timeLabel: $count catches"

                it.alpha = 1.0f
                it.postDelayed({ it.alpha = intensity }, 500)
            }

            binding.hourlyChartContainer.addView(barView)
        }
    }

    private fun setupRecentActivity() {
        val recents = dbHelper.getRecentLogs(10)
        if (recents.isEmpty()) {
            binding.rvRecentActivity.visibility = View.GONE
            return
        }
        binding.rvRecentActivity.visibility = View.VISIBLE
        binding.rvRecentActivity.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentActivity.adapter = RecentAdapter(recents) { item ->
            val bundle = bundleOf(
                "imagePath" to item.imagePath,
                "timestamp" to item.timestamp,
                "fishCount" to item.title,
                "details" to item.details,
                "placeName" to item.placeName,
                "lat" to item.lat.toFloat(),
                "lng" to item.lng.toFloat()
            )
            // Use the new action ID specific to AnalyticsFragment
            findNavController().navigate(R.id.action_analytics_to_detail, bundle)
        }
    }

    private fun setupTopLocations() {
        binding.topLocationsContainer.removeAllViews()
        val locations = dbHelper.getTopLocations()

        if (locations.isEmpty()) {
            addEmptyState(binding.topLocationsContainer)
            return
        }

        locations.forEachIndexed { index, (name, count) ->
            val view = LinearLayout(requireContext())
            view.orientation = LinearLayout.HORIZONTAL
            view.setPadding(0, 16, 0, 16)

            val tvName = TextView(requireContext())
            tvName.text = "${index + 1}. $name"
            tvName.textSize = 14f
            tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            tvName.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val tvCount = TextView(requireContext())
            tvCount.text = "$count catches"
            tvCount.textSize = 14f
            tvCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))

            view.addView(tvName)
            view.addView(tvCount)
            binding.topLocationsContainer.addView(view)

            // Divider
            if (index < locations.size - 1) {
                val divider = View(requireContext())
                divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                divider.setBackgroundColor(0xFFE0E0E0.toInt())
                binding.topLocationsContainer.addView(divider)
            }
        }
    }

    private fun setupBarChart(data: Map<String, Int>) {
        binding.chartContainer.removeAllViews()
        binding.chartLabels.removeAllViews()

        val maxVal = (data.values.maxOrNull() ?: 0).toFloat()

        if (maxVal == 0f) {
            addEmptyState(binding.chartContainer)
            return
        }

        val barViews = mutableListOf<View>()
        val labelViews = mutableListOf<TextView>()

        for ((day, count) in data) {
            val barLayout = LinearLayout(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = 8
            params.marginStart = 8
            barLayout.layoutParams = params
            barLayout.gravity = Gravity.BOTTOM
            barLayout.orientation = LinearLayout.VERTICAL

            val valueText = TextView(requireContext())
            valueText.text = count.toString()
            valueText.textSize = 12f
            valueText.setTypeface(null, Typeface.BOLD)
            valueText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            valueText.gravity = Gravity.CENTER
            valueText.visibility = View.INVISIBLE
            valueText.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }

            barLayout.addView(valueText)
            labelViews.add(valueText)

            val barView = View(requireContext())
            val heightPercent = if (maxVal > 0) (count / maxVal) else 0f

            val density = resources.displayMetrics.density
            val containerHeightPx = (140 * density).toInt()
            val barHeight = if (count > 0) (heightPercent * containerHeightPx).toInt().coerceAtLeast((4 * density).toInt()) else (2 * density).toInt()

            val barParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight)
            barView.layoutParams = barParams

            if (count > 0) {
                barView.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_bar_rounded)
                barView.alpha = 0.6f
            } else {
                barView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.overlay_gray))
                barView.alpha = 0.2f
            }

            barLayout.addView(barView)
            binding.chartContainer.addView(barLayout)
            barViews.add(barView)

            if (count > 0) {
                barLayout.setOnClickListener {
                    labelViews.forEach { it.visibility = View.INVISIBLE }
                    barViews.forEach { it.alpha = 0.6f }

                    valueText.visibility = View.VISIBLE
                    barView.alpha = 1.0f
                }
            }

            val labelView = TextView(requireContext())
            val labelParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            labelView.layoutParams = labelParams
            labelView.gravity = Gravity.CENTER
            labelView.text = day
            labelView.textSize = 10f
            labelView.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray))
            binding.chartLabels.addView(labelView)
        }
    }

    private fun addEmptyState(container: ViewGroup) {
        val empty = TextView(requireContext())
        empty.text = "No data available"
        empty.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, 32, 0, 32)
        empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray))
        container.addView(empty)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class RecentAdapter(private val items: List<HistoryItem>, private val onClick: (HistoryItem) -> Unit) : RecyclerView.Adapter<RecentAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgRecent)
            val title: TextView = v.findViewById(R.id.tvRecentTitle)
            val date: TextView = v.findViewById(R.id.tvRecentDate)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_analytics_recent, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title.ifEmpty { "Unknown" }
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            holder.date.text = sdf.format(Date(item.timestamp))

            val path = item.imagePath.split("|").firstOrNull() ?: ""
            if (File(path).exists()) {
                holder.img.setImageBitmap(BitmapFactory.decodeFile(path))
            } else {
                holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
    }
}