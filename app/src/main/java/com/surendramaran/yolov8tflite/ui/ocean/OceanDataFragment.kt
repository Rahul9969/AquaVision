package com.surendramaran.yolov8tflite.ui.ocean

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.surendramaran.yolov8tflite.R

/**
 * OceanDataFragment: Menu screen showing all 12 INCOIS-style ocean data
 * categories as a scrollable grid. Tapping any card navigates to
 * OceanDataMapFragment with the selected layer.
 */
class OceanDataFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_ocean_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_pfz)?.setOnClickListener {
            findNavController().navigate(R.id.fishingZoneFragment)
        }

        // Each card navigates to the map with a specific layer ID
        setupLayerButton(view, R.id.btn_sst, "sst")
        setupLayerButton(view, R.id.btn_chlorophyll, "chlorophyll")
        setupLayerButton(view, R.id.btn_winds, "winds")
        setupLayerButton(view, R.id.btn_currents, "currents")
        setupLayerButton(view, R.id.btn_wave_height, "wave_height")
        setupLayerButton(view, R.id.btn_swell, "swell")
        setupLayerButton(view, R.id.btn_mld, "mld")
        setupLayerButton(view, R.id.btn_ocean_forecast, "ocean_forecast")
        setupLayerButton(view, R.id.btn_heat_wave, "heat_wave")
        setupLayerButton(view, R.id.btn_tides, "tides")
        setupLayerButton(view, R.id.btn_tsunami, "tsunami")
    }

    private fun setupLayerButton(view: View, buttonId: Int, layerId: String) {
        view.findViewById<View>(buttonId)?.setOnClickListener {
            val bundle = Bundle().apply {
                putString("layerId", layerId)
            }
            findNavController().navigate(R.id.action_oceanData_to_map, bundle)
        }
    }
}
