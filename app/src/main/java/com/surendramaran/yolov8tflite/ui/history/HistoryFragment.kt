package com.surendramaran.yolov8tflite.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.android.material.chip.ChipGroup
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.DatabaseHelper
import com.surendramaran.yolov8tflite.data.SyncWorker
import java.util.concurrent.TimeUnit

class HistoryFragment : Fragment() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private var currentType = DatabaseHelper.TYPE_DETECTION

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())
        recyclerView = view.findViewById(R.id.historyRecyclerView)
        emptyText = view.findViewById(R.id.emptyStateText)
        val chipGroup = view.findViewById<ChipGroup>(R.id.historyFilterChips)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // --- NEW: Force Sync Logic ---
        view.findViewById<View>(R.id.btnForceSync).setOnClickListener {
            val unsynced = dbHelper.getUnsyncedLogs().size
            if (unsynced > 0) {
                Toast.makeText(context, "Syncing $unsynced items...", Toast.LENGTH_SHORT).show()
                triggerBackgroundSync()
            } else {
                Toast.makeText(context, "All items are already synced", Toast.LENGTH_SHORT).show()
            }
        }
        // -----------------------------

        chipGroup.setOnCheckedChangeListener { _, checkedId ->
            currentType = when (checkedId) {
                R.id.chipDetection -> DatabaseHelper.TYPE_DETECTION
                R.id.chipFreshness -> DatabaseHelper.TYPE_FRESHNESS
                R.id.chipVolume -> DatabaseHelper.TYPE_VOLUME
                else -> DatabaseHelper.TYPE_DETECTION
            }
            loadHistory()
        }

        // Initial load
        loadHistory()
    }

    private fun triggerBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            "HistoryUploadWork",
            ExistingWorkPolicy.APPEND, // Use APPEND to queue behind current works
            syncRequest
        )
    }

    private fun loadHistory() {
        val data = dbHelper.getHistoryByType(currentType)

        if (data.isEmpty()) {
            emptyText.text = when(currentType) {
                DatabaseHelper.TYPE_FRESHNESS -> getString(R.string.no_freshness_logs)
                DatabaseHelper.TYPE_VOLUME -> getString(R.string.no_volume_logs)
                else -> getString(R.string.no_detections_found)
            }
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            val adapter = HistoryAdapter(data) { item, imageView ->
                val bundle = bundleOf(
                    "imagePath" to item.imagePath,
                    "timestamp" to item.timestamp,
                    "fishCount" to item.title,
                    "details" to item.details,
                    "placeName" to item.placeName,
                    "lat" to item.lat.toFloat(),
                    "lng" to item.lng.toFloat()
                )

                val extras = FragmentNavigatorExtras(
                    imageView to item.imagePath
                )

                findNavController().navigate(
                    R.id.action_history_to_detail,
                    bundle,
                    null,
                    extras
                )
            }
            recyclerView.adapter = adapter
        }
    }
}