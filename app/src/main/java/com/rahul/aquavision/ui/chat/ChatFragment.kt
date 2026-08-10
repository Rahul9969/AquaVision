package com.rahul.aquavision.ui.chat

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.rahul.aquavision.R
import com.rahul.aquavision.ml.LlmHelper
import com.rahul.aquavision.ml.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private var llmHelper: LlmHelper? = null
    private lateinit var modelManager: ModelManager
    private lateinit var chatAdapter: ChatAdapter

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnScrollDown: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var tvModelBadge: TextView

    // Overlay Components
    private lateinit var progressOverlay: View
    private lateinit var tvProgress: TextView
    private lateinit var tvDownloadLink: TextView
    private lateinit var btnLoadModel: Button
    private lateinit var progressBar: ProgressBar

    private var isGenerating = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track which model path is currently loaded to detect switches
    private var currentModelPath: String? = null

    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loadModelFromUri(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind Views
        rvChat = view.findViewById(R.id.rvChat)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)
        btnScrollDown = view.findViewById(R.id.btnScrollDown)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvModelBadge = view.findViewById(R.id.tvModelBadge)

        progressOverlay = view.findViewById(R.id.progressOverlay)
        tvProgress = view.findViewById(R.id.tvProgress)
        tvDownloadLink = view.findViewById(R.id.tvDownloadLink)
        btnLoadModel = view.findViewById(R.id.btnLoadModel)
        progressBar = view.findViewById(R.id.progressBar)

        setupRecyclerView()
        modelManager = ModelManager(requireContext())

        checkAndInitModel()

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty() && !isGenerating) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }

        // Allow sending with keyboard action button
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = etMessage.text.toString().trim()
                if (text.isNotEmpty() && !isGenerating) {
                    sendMessage(text)
                    etMessage.text.clear()
                }
                true
            } else false
        }

        btnLoadModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        // --- Download Link Logic (Forces Browser) ---
        tvDownloadLink.paintFlags = tvDownloadLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        tvDownloadLink.setOnClickListener {
            val url = "https://drive.google.com/uc?export=download&id=1_BguJIGFpWjbTkJbd-wUyJ1PS0NgN77L"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)

            // 1. Try to force Chrome (most reliable way to avoid Drive App)
            intent.setPackage("com.android.chrome")

            try {
                startActivity(intent)
            } catch (e: Exception) {
                // 2. If Chrome is missing, remove the package lock and let the system pick any browser
                intent.setPackage(null)
                try {
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(context, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Manual Scroll Down Button Logic
        btnScrollDown.setOnClickListener {
            if (chatAdapter.itemCount > 0) {
                rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    /**
     * FIXED: Detect model path changes on resume and re-initialize.
     * This handles the case where the user switches models from MoreFragment.
     */
    override fun onResume() {
        super.onResume()
        if (!::modelManager.isInitialized) return

        val latestPath = modelManager.getModelPath()
        if (currentModelPath != null && currentModelPath != latestPath && modelManager.isModelReady()) {
            // Model was switched — reload
            currentModelPath = latestPath
            llmHelper?.close()
            llmHelper = null
            updateModelBadge()
            initializeLlm()
        }
    }

    private fun checkAndInitModel() {
        if (modelManager.isModelReady()) {
            progressOverlay.visibility = View.GONE
            tvProgress.text = ""
            updateModelBadge()
            initializeLlm()
        } else {
            progressOverlay.visibility = View.VISIBLE
            tvProgress.text = getString(R.string.model_not_found_short)
            // Show buttons
            btnLoadModel.visibility = View.VISIBLE
            tvDownloadLink.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            tvModelBadge.text = "No model"
        }
    }

    private fun updateModelBadge() {
        val displayName = modelManager.getActiveModelDisplayName()
        tvModelBadge.text = displayName
    }

    private fun loadModelFromUri(uri: Uri) {
        tvProgress.text = getString(R.string.initializing_copy)
        btnLoadModel.visibility = View.GONE
        tvDownloadLink.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // Extract original filename for model type detection
            var originalFilename = "model.task"
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        originalFilename = cursor.getString(nameIndex) ?: "model.task"
                    }
                }
            }

            val success = modelManager.copyModelFromUri(uri, originalFilename) { progress ->
                launch(Dispatchers.Main) {
                    tvProgress.text = getString(R.string.copying_model_progress, progress)
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    tvProgress.text = getString(R.string.copy_complete)
                    checkAndInitModel()
                } else {
                    tvProgress.text = getString(R.string.failed_to_copy_file)
                    btnLoadModel.visibility = View.VISIBLE
                    tvDownloadLink.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rvChat.layoutManager = layoutManager
        rvChat.adapter = chatAdapter
        rvChat.itemAnimator = null  // Disable default animations for smoother streaming

        rvChat.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (recyclerView.canScrollVertically(1)) {
                    btnScrollDown.visibility = View.VISIBLE
                } else {
                    btnScrollDown.visibility = View.GONE
                }
            }
        })
    }

    private fun initializeLlm() {
        tvStatus.text = getString(R.string.loading)
        tvStatus.setTextColor(0xFFFBBF24.toInt()) // amber while loading

        currentModelPath = modelManager.getModelPath()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val helper = LlmHelper(requireContext(), currentModelPath!!)
                helper.initModel()
                llmHelper = helper

                launch(Dispatchers.Main) {
                    tvStatus.text = getString(R.string.online)
                    tvStatus.setTextColor(0xFF22D3EE.toInt()) // cyan when ready
                    updateModelBadge()

                    // Show welcome message with suggested questions
                    val modelName = modelManager.getActiveModelDisplayName()
                    chatAdapter.addMessage(
                        "🐟 **Fish AI** is ready! (Model: $modelName)\n\n" +
                        "I can help you with:\n" +
                        "• Fish species identification\n" +
                        "• Weight & volume estimation\n" +
                        "• Freshness assessment\n" +
                        "• Protected species info\n" +
                        "• Fishing regulations\n\n" +
                        "Try asking: *\"Tell me about hilsa\"* or *\"How to check fish freshness?\"*",
                        false
                    )
                    rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                    progressOverlay.visibility = View.GONE
                }
            } catch (e: Throwable) {
                launch(Dispatchers.Main) {
                    tvStatus.text = "Error"
                    tvStatus.setTextColor(0xFFEF4444.toInt()) // red on error
                    progressOverlay.visibility = View.VISIBLE
                    tvProgress.text = getString(R.string.error_ram_low_or_model_invalid, e.message)
                    btnLoadModel.visibility = View.VISIBLE
                    tvDownloadLink.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun sendMessage(userText: String) {
        if (llmHelper == null) {
            Toast.makeText(context, "AI model not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        isGenerating = true
        btnSend.alpha = 0.5f

        // Warn about restricted topics that the LLM will refuse
        val restrictedPrefixes = listOf("catch", "kill", "hunt", "trap", "capture", "fish for", "catch a", "catch the", "can i catch")
        val isRestrictedQuery = restrictedPrefixes.any { userText.lowercase().startsWith(it) }

        chatAdapter.addMessage(userText, true)
        rvChat.scrollToPosition(chatAdapter.itemCount - 1)

        // Show typing indicator
        chatAdapter.addTypingIndicator()
        rvChat.scrollToPosition(chatAdapter.itemCount - 1)

        tvStatus.text = getString(R.string.typing)
        tvStatus.setTextColor(0xFFFBBF24.toInt())

        lifecycleScope.launch(Dispatchers.IO) {
            // ── Batched streaming: collect tokens on IO thread,
            //    push UI updates at most every 80ms via Handler ──
            val buffer = StringBuilder()
            var typingRemoved = false
            var uiUpdatePending = false

            val flushRunnable = Runnable {
                uiUpdatePending = false
                val snapshot = synchronized(buffer) { buffer.toString() }
                chatAdapter.updateLastMessage(snapshot)
                if (!rvChat.canScrollVertically(1)) {
                    rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }

            try {
                llmHelper!!.generateResponse(userText).collect { partialString ->
                    // Remove typing indicator on first token (once)
                    if (!typingRemoved) {
                        typingRemoved = true
                        withContext(Dispatchers.Main) {
                            chatAdapter.removeTypingIndicator()
                            chatAdapter.addMessage("", false)
                            rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }

                    // Accumulate tokens in buffer (no main-thread switch per token)
                    synchronized(buffer) {
                        if (partialString.length > buffer.length && partialString.startsWith(buffer.toString())) {
                            buffer.clear()
                            buffer.append(partialString)
                        } else {
                            buffer.append(partialString)
                        }
                    }

                    // Schedule a throttled UI flush (once per frame ~16ms)
                    if (!uiUpdatePending) {
                        uiUpdatePending = true
                        mainHandler.postDelayed(flushRunnable, 16)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!typingRemoved) {
                        chatAdapter.removeTypingIndicator()
                        chatAdapter.addMessage("", false)
                    }
                    chatAdapter.updateLastMessage(getString(R.string.chat_error, e.message))
                }
            }

            // Final flush — ensure the complete response is shown
            withContext(Dispatchers.Main) {
                mainHandler.removeCallbacks(flushRunnable)
                val finalText = synchronized(buffer) { buffer.toString() }

                // Handle empty response
                val displayText = if (finalText.isBlank()) {
                    "I couldn't generate a response. Please try rephrasing your question."
                } else {
                    finalText
                }

                // Append disclaimer to responses about fishing/catching
                val finalTextWithDisclaimer = if (isRestrictedQuery && displayText.isNotBlank()) {
                    "$displayText\n\n_⚠️ AI-assisted estimate only. Always comply with local fisheries regulations._"
                } else {
                    displayText
                }

                chatAdapter.updateLastMessage(finalTextWithDisclaimer)
                rvChat.scrollToPosition(chatAdapter.itemCount - 1)

                isGenerating = false
                btnSend.alpha = 1f
                tvStatus.text = getString(R.string.online)
                tvStatus.setTextColor(0xFF22D3EE.toInt())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // FIXED: check nullability instead of lateinit — avoids UninitializedPropertyAccessException
        try { llmHelper?.close() } catch (e: Exception) {}
    }
}