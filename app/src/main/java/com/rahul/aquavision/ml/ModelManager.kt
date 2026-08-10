package com.rahul.aquavision.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Manages multiple LLM model files.
 * Supports Gemma, Qwen, TinyLlama, and other MediaPipe-compatible models.
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_TYPE_GEMMA = "gemma"
        const val MODEL_TYPE_QWEN = "qwen"
        const val MODEL_TYPE_TINYLLAMA = "tinyllama"
        const val MODEL_TYPE_LLAMA = "llama"
        const val MODEL_TYPE_UNKNOWN = "unknown"

        private val MODEL_PATTERNS = mapOf(
            MODEL_TYPE_GEMMA to listOf("gemma", "gemma2b"),
            MODEL_TYPE_QWEN to listOf("qwen", "qwen2", "qwen2.5"),
            MODEL_TYPE_TINYLLAMA to listOf("tinyllama", "tiny-llama"),
            MODEL_TYPE_LLAMA to listOf("llama", "llama2", "llama3")
        )

        private const val PREFS_NAME = "AquaVisionPrefs"
        private const val KEY_ACTIVE_MODEL = "active_llm_model"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun detectModelType(filename: String): String {
        val lower = filename.lowercase()
        for ((type, patterns) in MODEL_PATTERNS) {
            if (patterns.any { lower.contains(it) }) {
                return type
            }
        }
        return MODEL_TYPE_UNKNOWN
    }

    fun getPromptTemplate(modelType: String): PromptTemplate {
        return when (modelType) {
            MODEL_TYPE_GEMMA -> PromptTemplate(
                userStart = "<start_of_turn>user\n",
                userEnd = "<end_of_turn>\n",
                modelStart = "<start_of_turn>model\n",
                modelEnd = "<end_of_turn>\n",
                systemPrefix = "<start_of_turn>user\n",
                systemEnd = "<end_of_turn>\n<start_of_turn>model\n"
            )
            MODEL_TYPE_QWEN -> PromptTemplate(
                userStart = "<|im_start|>user\n",
                userEnd = "<|im_end|>\n",
                modelStart = "<|im_start|>assistant\n",
                modelEnd = "<|im_end|>\n",
                systemPrefix = "<|im_start|>system\n",
                systemEnd = "<|im_end|>\n<|im_start|>user\n"
            )
            MODEL_TYPE_TINYLLAMA, MODEL_TYPE_LLAMA -> PromptTemplate(
                userStart = "[INST] ",
                userEnd = " [/INST]",
                modelStart = " ",
                modelEnd = "</s>",
                systemPrefix = "[INST] <<SYS>>\n",
                systemEnd = "\n<</SYS>> [/INST]",
                systemSuffix = " [/INST]"
            )
            else -> PromptTemplate(
                userStart = "User: ",
                userEnd = "\n",
                modelStart = "Assistant: ",
                modelEnd = "\n",
                systemPrefix = "System: ",
                systemEnd = "\n",
                systemSuffix = "\n"
            )
        }
    }

    fun isModelReady(): Boolean {
        val supportedFiles = listOf("gemma.task", "qwen.task", "tinyllama.task", "llama.task", "model.task")
        return supportedFiles.any { filename ->
            val file = File(context.filesDir, filename)
            file.exists() && file.length() > 0
        }
    }

    fun isModelLoaded(modelType: String): Boolean {
        val filename = when (modelType) {
            MODEL_TYPE_GEMMA -> "gemma.task"
            MODEL_TYPE_QWEN -> "qwen.task"
            MODEL_TYPE_TINYLLAMA -> "tinyllama.task"
            MODEL_TYPE_LLAMA -> "llama.task"
            else -> "model.task"
        }
        val file = File(context.filesDir, filename)
        return file.exists() && file.length() > 0
    }

    fun getModelSize(modelType: String): Long {
        val filename = when (modelType) {
            MODEL_TYPE_GEMMA -> "gemma.task"
            MODEL_TYPE_QWEN -> "qwen.task"
            MODEL_TYPE_TINYLLAMA -> "tinyllama.task"
            MODEL_TYPE_LLAMA -> "llama.task"
            else -> "model.task"
        }
        val file = File(context.filesDir, filename)
        return if (file.exists()) file.length() else 0
    }

    fun getActiveModelType(): String {
        val path = getModelPath()
        val filename = File(path).name
        return detectModelType(filename)
    }

    fun getActiveModelDisplayName(): String {
        return when (getActiveModelType()) {
            MODEL_TYPE_GEMMA -> "Gemma 2B"
            MODEL_TYPE_QWEN -> "Qwen 2.5-0.5B"
            MODEL_TYPE_TINYLLAMA -> "TinyLlama 1.1B"
            MODEL_TYPE_LLAMA -> "Llama"
            else -> "Custom Model"
        }
    }

    fun setActiveModel(modelType: String) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, modelType).apply()
    }

    fun getModelPath(): String {
        // Check user's preferred model first
        val preferredModel = prefs.getString(KEY_ACTIVE_MODEL, null)
        if (preferredModel != null) {
            val filename = when (preferredModel) {
                MODEL_TYPE_GEMMA -> "gemma.task"
                MODEL_TYPE_QWEN -> "qwen.task"
                MODEL_TYPE_TINYLLAMA -> "tinyllama.task"
                MODEL_TYPE_LLAMA -> "llama.task"
                else -> "model.task"
            }
            val file = File(context.filesDir, filename)
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
        }

        // Fallback: check all models
        val supportedFiles = listOf("gemma.task", "qwen.task", "tinyllama.task", "llama.task", "model.task")
        for (filename in supportedFiles) {
            val file = File(context.filesDir, filename)
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
        }
        return File(context.filesDir, "model.task").absolutePath
    }

    fun copyModelFromUri(uri: Uri, originalFilename: String, onProgress: (Int) -> Unit): Boolean {
        return try {
            val resolver = context.contentResolver
            val modelType = detectModelType(originalFilename)

            val targetFilename = when (modelType) {
                MODEL_TYPE_GEMMA -> "gemma.task"
                MODEL_TYPE_QWEN -> "qwen.task"
                MODEL_TYPE_TINYLLAMA -> "tinyllama.task"
                MODEL_TYPE_LLAMA -> "llama.task"
                else -> "model.task"
            }

            var fileSize = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(File(context.filesDir, targetFilename)).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes = input.read(buffer)
                    var totalBytes = 0L
                    var lastProgress = 0

                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        totalBytes += bytes

                        if (fileSize > 0) {
                            val progress = ((totalBytes * 100) / fileSize).toInt()
                            if (progress > lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                        bytes = input.read(buffer)
                    }
                }
            }

            // Auto-set as active model after loading
            if (modelType != MODEL_TYPE_UNKNOWN) {
                setActiveModel(modelType)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun copyModelFromUri(uri: Uri, onProgress: (Int) -> Unit): Boolean {
        var originalFilename = "model.task"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    originalFilename = cursor.getString(nameIndex) ?: "model.task"
                }
            }
        }
        return copyModelFromUri(uri, originalFilename, onProgress)
    }
}

data class PromptTemplate(
    val userStart: String,
    val userEnd: String,
    val modelStart: String,
    val modelEnd: String,
    val systemPrefix: String,
    val systemEnd: String,
    val systemSuffix: String = ""
)