package com.surendramaran.yolov8tflite.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.data.HistoryItem
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

        holder.counts.text = item.title
        holder.details.text = item.details

        // FIX: Handle multiple paths separated by "|"
        // If it's a volume log, paths are like "path1.jpg|path2.jpg"
        // We just take the first one for the thumbnail.
        val firstPath = item.imagePath.split("|").firstOrNull() ?: ""

        val imgFile = File(firstPath)
        if (imgFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            holder.image.setImageBitmap(bitmap)
        } else {
            // Fallback placeholder
            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        holder.image.transitionName = item.imagePath

        holder.itemView.setOnClickListener {
            onItemClick(item, holder.image)
        }
    }

    override fun getItemCount() = historyList.size
}