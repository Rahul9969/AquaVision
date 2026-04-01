package com.surendramaran.yolov8tflite.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.databinding.FragmentLanguageBinding

class LanguageFragment : Fragment() {

    private var _binding: FragmentLanguageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Pre-select the current language
        val localeList = AppCompatDelegate.getApplicationLocales()
        val currentLang = if (!localeList.isEmpty) localeList[0]?.language else "en"

        val count = binding.languageGroup.childCount
        for (i in 0 until count) {
            val v = binding.languageGroup.getChildAt(i)
            if (v is RadioButton && v.tag == currentLang) {
                v.isChecked = true
                break
            }
        }

        // 2. Change Language immediately on selection
        binding.languageGroup.setOnCheckedChangeListener { group, checkedId ->
            val radioButton = group.findViewById<RadioButton>(checkedId)
            val langCode = radioButton.tag.toString()

            // This recreates the Activity to apply the new language
            val appLocale = LocaleListCompat.forLanguageTags(langCode)
            AppCompatDelegate.setApplicationLocales(appLocale)
        }

        // 3. Continue to Profile Screen
        binding.btnContinue.setOnClickListener {
            // We pass a flag so ProfileFragment knows it's part of the onboarding flow
            val bundle = bundleOf("isOnboarding" to true)
            findNavController().navigate(R.id.action_language_to_profile, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}