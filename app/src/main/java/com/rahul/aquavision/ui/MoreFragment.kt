package com.rahul.aquavision.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rahul.aquavision.R
import com.rahul.aquavision.databinding.DialogAiModelBinding
import com.rahul.aquavision.databinding.FragmentMoreBinding
import com.rahul.aquavision.ml.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    private lateinit var modelManager: ModelManager
    private var dialogBinding: DialogAiModelBinding? = null
    private var bottomSheetDialog: BottomSheetDialog? = null

    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loadModelFromUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modelManager = ModelManager(requireContext())

        binding.btnProfile.setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
        binding.root.findViewById<View>(R.id.btn_analytics)?.setOnClickListener {
            findNavController().navigate(R.id.analyticsFragment)
        }

        binding.btnFreshness.setOnClickListener {
            findNavController().navigate(R.id.freshnessFragment)
        }

        binding.btnMap.setOnClickListener { findNavController().navigate(R.id.mapFragment) }
        binding.btnChat.setOnClickListener { findNavController().navigate(R.id.chatFragment) }
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }

        binding.root.findViewById<View>(R.id.btn_geofence)?.setOnClickListener {
            findNavController().navigate(R.id.geoFenceFragment)
        }

        binding.root.findViewById<View>(R.id.btn_ar_measure)?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), com.rahul.aquavision.ar.ArFishMeasureActivity::class.java)
            startActivity(intent)
        }

        binding.root.findViewById<View>(R.id.btn_ai_model)?.setOnClickListener {
            showAiModelDialog()
        }
    }

    /**
     * Shows a modern dark-themed BottomSheetDialog for AI model management.
     */
    private fun showAiModelDialog() {
        dialogBinding = DialogAiModelBinding.inflate(layoutInflater)

        bottomSheetDialog = BottomSheetDialog(requireContext()).apply {
            setContentView(dialogBinding!!.root)
            // Make the sheet background transparent so our custom bg_model_sheet shows
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)
        }

        updateModelStatus()

        val currentType = modelManager.getActiveModelType()
        when (currentType) {
            ModelManager.MODEL_TYPE_GEMMA -> dialogBinding!!.rbGemma.isChecked = true
            ModelManager.MODEL_TYPE_QWEN -> dialogBinding!!.rbQwen.isChecked = true
            ModelManager.MODEL_TYPE_TINYLLAMA -> dialogBinding!!.rbTinyllama.isChecked = true
        }

        // Highlight the active card
        updateCardHighlights(currentType)

        // Card click handlers (the whole card is tappable, not just the radio)
        dialogBinding!!.cardGemma.setOnClickListener {
            dialogBinding!!.rbGemma.isChecked = true
            selectModelAndLoad(ModelManager.MODEL_TYPE_GEMMA)
        }
        dialogBinding!!.cardQwen.setOnClickListener {
            dialogBinding!!.rbQwen.isChecked = true
            selectModelAndLoad(ModelManager.MODEL_TYPE_QWEN)
        }
        dialogBinding!!.cardTinyllama.setOnClickListener {
            dialogBinding!!.rbTinyllama.isChecked = true
            selectModelAndLoad(ModelManager.MODEL_TYPE_TINYLLAMA)
        }

        // Radio button handlers
        dialogBinding!!.rbGemma.setOnClickListener { selectModelAndLoad(ModelManager.MODEL_TYPE_GEMMA) }
        dialogBinding!!.rbQwen.setOnClickListener { selectModelAndLoad(ModelManager.MODEL_TYPE_QWEN) }
        dialogBinding!!.rbTinyllama.setOnClickListener { selectModelAndLoad(ModelManager.MODEL_TYPE_TINYLLAMA) }

        dialogBinding!!.btnLoadModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        bottomSheetDialog!!.show()
    }

    /**
     * Updates the card backgrounds to highlight the active model.
     */
    private fun updateCardHighlights(activeType: String) {
        val db = dialogBinding ?: return
        db.cardGemma.setBackgroundResource(
            if (activeType == ModelManager.MODEL_TYPE_GEMMA) R.drawable.bg_model_card_active else R.drawable.bg_model_card
        )
        db.cardQwen.setBackgroundResource(
            if (activeType == ModelManager.MODEL_TYPE_QWEN) R.drawable.bg_model_card_active else R.drawable.bg_model_card
        )
        db.cardTinyllama.setBackgroundResource(
            if (activeType == ModelManager.MODEL_TYPE_TINYLLAMA) R.drawable.bg_model_card_active else R.drawable.bg_model_card
        )
    }

    private fun updateModelStatus() {
        val db = dialogBinding ?: return

        db.tvCurrentModel.text = modelManager.getActiveModelDisplayName()

        val size = modelManager.getModelSize(modelManager.getActiveModelType())
        db.tvModelSize.text = if (size > 0) "Size: ${formatFileSize(size)}" else "No model loaded"

        val gemmaLoaded = modelManager.isModelLoaded(ModelManager.MODEL_TYPE_GEMMA)
        val qwenLoaded = modelManager.isModelLoaded(ModelManager.MODEL_TYPE_QWEN)
        val tinyllamaLoaded = modelManager.isModelLoaded(ModelManager.MODEL_TYPE_TINYLLAMA)

        // Status badges with colored text
        db.tvGemmaStatus.text = if (gemmaLoaded) "✓ Loaded" else "Not loaded"
        db.tvGemmaStatus.setTextColor(if (gemmaLoaded) 0xFF22D3EE.toInt() else 0xFF4A5580.toInt())

        db.tvQwenStatus.text = if (qwenLoaded) "✓ Loaded" else "Not loaded"
        db.tvQwenStatus.setTextColor(if (qwenLoaded) 0xFF22D3EE.toInt() else 0xFF4A5580.toInt())

        db.tvTinyllamaStatus.text = if (tinyllamaLoaded) "✓ Loaded" else "Not loaded"
        db.tvTinyllamaStatus.setTextColor(if (tinyllamaLoaded) 0xFF22D3EE.toInt() else 0xFF4A5580.toInt())
    }

    private fun selectModelAndLoad(modelType: String) {
        val isLoaded = modelManager.isModelLoaded(modelType)
        if (isLoaded) {
            modelManager.setActiveModel(modelType)
            updateModelStatus()
            updateCardHighlights(modelType)
            Toast.makeText(requireContext(), "${getModelDisplayName(modelType)} set as active ✓", Toast.LENGTH_SHORT).show()
        } else {
            // Not loaded — prompt the user to load the file
            Toast.makeText(requireContext(), "Model not loaded — select a model file below", Toast.LENGTH_SHORT).show()
            pickModelLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun loadModelFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var originalFilename = "model.task"
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        originalFilename = cursor.getString(nameIndex) ?: "model.task"
                    }
                }
            }

            val success = modelManager.copyModelFromUri(uri, originalFilename) { }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(requireContext(), "Model loaded! ✓", Toast.LENGTH_LONG).show()
                    updateModelStatus()
                    val activeType = modelManager.getActiveModelType()
                    updateCardHighlights(activeType)
                    when (activeType) {
                        ModelManager.MODEL_TYPE_GEMMA -> dialogBinding?.rbGemma?.isChecked = true
                        ModelManager.MODEL_TYPE_QWEN -> dialogBinding?.rbQwen?.isChecked = true
                        ModelManager.MODEL_TYPE_TINYLLAMA -> dialogBinding?.rbTinyllama?.isChecked = true
                    }
                } else {
                    Toast.makeText(requireContext(), "Failed to load model", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getModelDisplayName(type: String): String = when (type) {
        ModelManager.MODEL_TYPE_GEMMA -> "Gemma 2B"
        ModelManager.MODEL_TYPE_QWEN -> "Qwen 2.5-0.5B"
        ModelManager.MODEL_TYPE_TINYLLAMA -> "TinyLlama 1.1B"
        else -> "Unknown"
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            "English", "हिन्दी (Hindi)", "தமிழ் (Tamil)", "മലയാളം (Malayalam)", "తెలుగు (Telugu)", "বাংলা (Bengali)",
            "मराठी (Marathi)", "ગુજરાતી (Gujarati)", "ಕನ್ನಡ (Kannada)", "ଓଡ଼ିଆ (Odia)",
            "ਪੰਜਾਬੀ (Punjabi)", "অসমীয়া (Assamese)", "اردو (Urdu)", "कोंकणी (Konkani)", "संस्कृत (Sanskrit)"
        )
        val codes = arrayOf("en", "hi", "ta", "ml", "te", "bn", "mr", "gu", "kn", "or", "pa", "as", "ur", "kok", "sa")

        val localeList = AppCompatDelegate.getApplicationLocales()
        val currentLocaleCode = if (!localeList.isEmpty) localeList[0]?.language else "en"
        val currentIndex = codes.indexOf(currentLocaleCode).takeIf { it != -1 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Choose Language / மொழி")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                setAppLocale(codes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun setAppLocale(languageCode: String) {
        val prefs = requireContext().getSharedPreferences("AquaVisionPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", languageCode).apply()
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        requireActivity().recreate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
        dialogBinding = null
        _binding = null
    }
}