package com.surendramaran.yolov8tflite.ml.segmentation.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.surendramaran.yolov8tflite.databinding.ItemImageBinding
import com.surendramaran.yolov8tflite.ml.segmentation.AnalysisResult

class ViewPagerAdapter(private val results: MutableList<AnalysisResult>) : RecyclerView.Adapter<ViewPagerAdapter.ViewPagerHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewPagerHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ItemImageBinding.inflate(layoutInflater, parent, false)
        return ViewPagerHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewPagerHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount(): Int {
        return results.size
    }

    class ViewPagerHolder(private var binding: ItemImageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: AnalysisResult) {
            binding.image.setImageBitmap(result.original)
            binding.overlay.setImageBitmap(result.overlay)
            binding.tvDescription.text = result.description
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateImages(newResults: List<AnalysisResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }
}