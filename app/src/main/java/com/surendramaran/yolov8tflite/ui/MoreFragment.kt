package com.surendramaran.yolov8tflite.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.databinding.FragmentMoreBinding

class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnProfile.setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
        binding.root.findViewById<View>(R.id.btn_analytics)?.setOnClickListener {
            findNavController().navigate(R.id.analyticsFragment)
        }

        // Updated: History moved to bottom nav, Freshness moved here
        binding.btnFreshness.setOnClickListener {
            findNavController().navigate(R.id.freshnessFragment)
        }

        binding.btnMap.setOnClickListener { findNavController().navigate(R.id.mapFragment) }
        binding.btnChat.setOnClickListener { findNavController().navigate(R.id.chatFragment) }
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }

        // Geo-Fence Protection
        binding.root.findViewById<View>(R.id.btn_geofence)?.setOnClickListener {
            findNavController().navigate(R.id.geoFenceFragment)
        }

        // AR Fish Measure
        binding.root.findViewById<View>(R.id.btn_ar_measure)?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), com.surendramaran.yolov8tflite.ar.ArFishMeasureActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("English", "हिन्दी (Hindi)", "தமிழ் (Tamil)", "മലയാളം (Malayalam)", "తెలుగు (Telugu)", "বাংলা (Bengali)")
        val codes = arrayOf("en", "hi", "ta", "ml", "te", "bn")

        val localeList = AppCompatDelegate.getApplicationLocales()
        val currentLocaleCode = if (!localeList.isEmpty) localeList[0]?.language else "en"
        val currentIndex = codes.indexOf(currentLocaleCode).takeIf { it != -1 } ?: 0

        AlertDialog.Builder(requireContext())
            .setTitle("Choose Language / மொழி")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                setAppLocale(codes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun setAppLocale(languageCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}