package com.rahul.aquavision.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.rahul.aquavision.R
import com.rahul.aquavision.databinding.FragmentMoreBinding

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
            val intent = android.content.Intent(requireContext(), com.rahul.aquavision.ar.ArFishMeasureActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            "English", "हिन्दी (Hindi)", "தமிழ் (Tamil)", "മലയാളം (Malayalam)", "తెలుగు (Telugu)", "বাংলা (Bengali)",
            "मराठी (Marathi)", "ગુજરાતી (Gujarati)", "ಕನ್ನಡ (Kannada)", "ଓଡ଼ିଆ (Odia)", 
            "ਪੰਜਾਬੀ (Punjabi)", "অসমীয়া (Assamese)", "اردو (Urdu)", "कोंकणी (Konkani)", "संस्कृत (Sanskrit)"
        )
        val codes = arrayOf(
            "en", "hi", "ta", "ml", "te", "bn", "mr", "gu", "kn", "or", "pa", "as", "ur", "kok", "sa"
        )

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
        // Save preference to force legacy base context wrapper
        val prefs = requireContext().getSharedPreferences("AquaVisionPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", languageCode).apply()

        // 1. AndroidX Delegate
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        
        // 2. Hardcoded override for immediate effect (Legacy fallback)
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val resources = requireContext().resources
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        
        // 3. Force UI refresh
        requireActivity().recreate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}