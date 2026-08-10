package com.rahul.aquavision.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.rahul.aquavision.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * LLM inference helper supporting multiple model types:
 * - Gemma (Google)
 * - Qwen (Alibaba)
 * - TinyLlama
 * - Llama variants
 *
 * Automatically detects model type and applies appropriate prompt templates.
 */
class LlmHelper(
    private val context: Context,
    private val modelPath: String
) {
    private var llmInference: LlmInference? = null
    private val knowledgeBase = FisheriesKnowledgeBase
    private val modelManager = ModelManager(context)

    // Auto-detect model type from path
    private val modelType: String by lazy {
        modelManager.detectModelType(File(modelPath).name)
    }

    // Get prompt template for detected model type
    private val promptTemplate: PromptTemplate by lazy {
        modelManager.getPromptTemplate(modelType)
    }

    // System prompt - enforces on-topic responses only
    private val systemPrompt = buildString {
        append("You are AquaVision fishing assistant ONLY for Indian fisheries and aquaculture topics. ")
        append("SCOPE: Fish species ID, weight/volume estimation, catch freshness, sustainable fishing, ")
        append("protected species (Wildlife Protection Act), fishing regulations, and app features. ")
        append("REFUSE: Any question not related to these topics. Respond: \"I can only answer questions about fishing, fish species, or the AquaVision app.\" ")
        append("NEVER suggest catching protected species (whale shark, sawfish, sea horse, turtle, dugong, dolphin). ")
        append("Follow Wildlife Protection Act. Estimates are indicative only, not for commercial use. ")
        append("Keep answers brief but informative.")
    }

    fun initModel() {
        val file = File(modelPath)
        if (!file.exists()) {
            throw RuntimeException(context.getString(R.string.model_not_found, modelPath))
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(512)  // Increased from 256 for more complete answers
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    /**
     * Generate response using streaming Flow.
     * Refuses off-topic queries without calling LLM.
     */
    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (llmInference == null) {
            trySend(context.getString(R.string.llm_not_ready))
            close()
            return@callbackFlow
        }

        // Check if query is on-topic before calling LLM
        if (!knowledgeBase.isOnTopic(prompt)) {
            trySend("I can only answer questions about fishing, fish species, or the AquaVision app features.\n\nTopics I can help with:\n- Fish species identification\n- Weight/volume estimation\n- Catch freshness\n- Protected species info\n- Fishing regulations\n- App usage")
            close()
            return@callbackFlow
        }

        // Build prompt with RAG context
        val contextHint = knowledgeBase.getQuickContext(prompt)
        val fullPrompt = if (contextHint == "OFF_TOPIC") {
            // Explicit off-topic keywords detected - refuse
            "${systemPrompt}\n\nQ: $prompt\nA: I can only answer questions about fishing or AquaVision app."
        } else {
            buildPrompt(prompt, contextHint)
        }

        try {
            llmInference!!.generateResponseAsync(fullPrompt) { partialResult, done ->
                // FIXED: Always send partial result first, THEN check done flag.
                // Previously close() was called before the final chunk could be sent.
                if (partialResult != null && partialResult.isNotEmpty()) {
                    trySend(partialResult)
                }
                if (done) {
                    close()
                }
            }
        } catch (e: Exception) {
            trySend(context.getString(R.string.llm_error, e.message))
            close()
        }

        awaitClose { }
    }

    /**
     * Generate response with species-specific context.
     */
    fun generateResponseWithSpecies(prompt: String, speciesName: String): Flow<String> = callbackFlow {
        if (llmInference == null) {
            trySend(context.getString(R.string.llm_not_ready))
            close()
            return@callbackFlow
        }

        val speciesContext = knowledgeBase.buildSpeciesContext(speciesName)
        val fullPrompt = buildPrompt(prompt, speciesContext)

        try {
            llmInference!!.generateResponseAsync(fullPrompt) { partialResult, done ->
                if (partialResult != null && partialResult.isNotEmpty()) {
                    trySend(partialResult)
                }
                if (done) {
                    close()
                }
            }
        } catch (e: Exception) {
            trySend(context.getString(R.string.llm_error, e.message))
            close()
        }

        awaitClose { }
    }

    /**
     * Build formatted prompt using model-specific template.
     */
    private fun buildPrompt(userPrompt: String, context: String = ""): String {
        val content = buildString {
            append(systemPrompt)
            if (context.isNotEmpty()) {
                append("\n\nContext: $context")
            }
            append("\n\nQ: $userPrompt\nA:")
        }

        return when (modelType) {
            ModelManager.MODEL_TYPE_GEMMA -> buildGemmaPrompt(content)
            ModelManager.MODEL_TYPE_QWEN -> buildQwenPrompt(content)
            ModelManager.MODEL_TYPE_TINYLLAMA, ModelManager.MODEL_TYPE_LLAMA -> buildLlamaPrompt(content)
            else -> buildGenericPrompt(content)
        }
    }

    private fun buildGemmaPrompt(content: String): String {
        return "${promptTemplate.userStart}$content${promptTemplate.userEnd}${promptTemplate.modelStart}"
    }

    private fun buildQwenPrompt(content: String): String {
        return "${promptTemplate.systemPrefix}You are a helpful assistant.${promptTemplate.systemEnd}$content${promptTemplate.modelStart}"
    }

    private fun buildLlamaPrompt(content: String): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>

            You are a helpful fishing assistant for Indian waters.

            <|eot_id|><|start_header_id|>user<|end_header_id|>

            $content
            <|eot_id|><|start_header_id|>assistant<|end_header_id|>

        """.trimIndent()
    }

    private fun buildGenericPrompt(content: String): String {
        return "${promptTemplate.userStart}$content${promptTemplate.userEnd}${promptTemplate.modelStart}"
    }

    /**
     * Get detected model type name for display purposes.
     */
    fun getModelTypeName(): String = modelType

    fun close() {
        llmInference = null
    }
}