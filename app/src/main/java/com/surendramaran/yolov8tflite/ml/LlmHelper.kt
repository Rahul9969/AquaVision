package com.surendramaran.yolov8tflite.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.surendramaran.yolov8tflite.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

class LlmHelper(
    private val context: Context,
    private val modelPath: String
) {
    private var llmInference: LlmInference? = null

    fun initModel() {
        val file = File(modelPath)
        if (!file.exists()) {
            throw RuntimeException(context.getString(R.string.model_not_found, modelPath))
        }

        // Initialize without a listener in options (Fixes 'Unresolved reference')
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    // Returns a Flow that emits strings as they are generated
    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (llmInference == null) {
            trySend(context.getString(R.string.llm_not_ready))
            close()
            return@callbackFlow
        }

        val formattedPrompt = "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$prompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

        try {
            // Use Async API for streaming
            llmInference!!.generateResponseAsync(formattedPrompt) { partialResult, done ->
                // Emit the new token/chunk to the Flow
                if (partialResult != null) {
                    trySend(partialResult)
                }
                // Close the flow when generation is complete
                if (done) {
                    close()
                }
            }
        } catch (e: Exception) {
            trySend(context.getString(R.string.llm_error, e.message))
            close()
        }

        // Keep the flow alive until closed
        awaitClose { }
    }

    fun close() {
        llmInference = null
    }
}