package com.rahul.aquavision.ui.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Build
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rahul.aquavision.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isTyping: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val animatedPositions = mutableSetOf<Int>()
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // ── Formatting cache: avoids re-parsing unchanged text ──
    private var cachedRawText: String = ""
    private var cachedFormatted: Spanned = SpannableStringBuilder("")

    companion object {
        const val VIEW_TYPE_USER = 0
        const val VIEW_TYPE_AI = 1
        const val VIEW_TYPE_TYPING = 2
    }

    fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        // Clear cache when a new message is added
        cachedRawText = ""
        cachedFormatted = SpannableStringBuilder("")
        notifyItemInserted(messages.size - 1)
    }

    fun addTypingIndicator() {
        messages.add(ChatMessage("", isUser = false, isTyping = true))
        notifyItemInserted(messages.size - 1)
    }

    fun removeTypingIndicator() {
        val idx = messages.indexOfLast { it.isTyping }
        if (idx >= 0) {
            messages.removeAt(idx)
            animatedPositions.remove(idx)
            notifyItemRemoved(idx)
        }
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            val lastMsg = messages[lastIndex]
            if (!lastMsg.isTyping) {
                messages[lastIndex] = lastMsg.copy(text = text)
                notifyItemChanged(lastIndex, "text_update")
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return when {
            msg.isTyping -> VIEW_TYPE_TYPING
            msg.isUser -> VIEW_TYPE_USER
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        val root = holder.itemView

        val aiRow = root.findViewById<LinearLayout>(R.id.aiRow)
        val userRow = root.findViewById<LinearLayout>(R.id.userRow)
        val typingRow = root.findViewById<LinearLayout>(R.id.typingRow)

        // Hide all rows first
        aiRow.visibility = View.GONE
        userRow.visibility = View.GONE
        typingRow.visibility = View.GONE

        when {
            message.isTyping -> {
                typingRow.visibility = View.VISIBLE
                startTypingAnimation(root)
            }
            message.isUser -> {
                userRow.visibility = View.VISIBLE
                val tvUserMessage = root.findViewById<TextView>(R.id.tvUserMessage)
                val tvUserTime = root.findViewById<TextView>(R.id.tvUserTime)
                tvUserMessage.text = message.text
                tvUserTime.text = timeFormat.format(Date(message.timestamp))
            }
            else -> {
                aiRow.visibility = View.VISIBLE
                val tvAiMessage = root.findViewById<TextView>(R.id.tvAiMessage)
                val tvAiTime = root.findViewById<TextView>(R.id.tvAiTime)
                tvAiMessage.text = getFormattedText(message.text)
                tvAiTime.text = timeFormat.format(Date(message.timestamp))
            }
        }

        // Slide-in animation — only on first bind
        if (!animatedPositions.contains(position) && !message.isTyping) {
            animatedPositions.add(position)
            val animRes = if (message.isUser) R.anim.slide_in_right else R.anim.slide_in_left
            val anim = AnimationUtils.loadAnimation(root.context, animRes)
            root.startAnimation(anim)
        }
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "text_update") {
            // Partial bind: only update the text, skip animation
            val message = messages[position]
            val root = holder.itemView
            if (!message.isUser && !message.isTyping) {
                val tvAiMessage = root.findViewById<TextView>(R.id.tvAiMessage)
                tvAiMessage?.text = getFormattedText(message.text)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    /**
     * Returns cached formatted text if the raw text hasn't changed,
     * otherwise re-formats and caches the result.
     */
    private fun getFormattedText(raw: String): Spanned {
        if (raw == cachedRawText) return cachedFormatted
        cachedRawText = raw
        cachedFormatted = formatMarkdown(raw)
        return cachedFormatted
    }

    /**
     * Converts markdown-formatted text to a formatted Spanned for display.
     * Handles: **bold**, * bullet points, ## headers, and paragraph spacing.
     */
    private fun formatMarkdown(raw: String): Spanned {
        if (raw.isBlank()) return SpannableStringBuilder("")

        // Convert markdown to HTML
        val html = raw
            // Escape HTML characters
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            // Headers: ## Header → <b><big>Header</big></b>
            .replace(Regex("""^#{1,3}\s+(.+)$""", RegexOption.MULTILINE)) {
                "<br><b><big>${it.groupValues[1].trim()}</big></b><br>"
            }
            // Bold: **text** → <b>text</b>
            .replace(Regex("""\*\*(.+?)\*\*""")) {
                "<b>${it.groupValues[1]}</b>"
            }
            // Bullet points: lines starting with * or - → bullet character
            .replace(Regex("""^\s*[\*\-]\s+(.+)$""", RegexOption.MULTILINE)) {
                "&nbsp;&nbsp;• ${it.groupValues[1]}<br>"
            }
            // Numbered lists: lines starting with 1. 2. etc.
            .replace(Regex("""^\s*(\d+)\.\s+(.+)$""", RegexOption.MULTILINE)) {
                "&nbsp;&nbsp;${it.groupValues[1]}. ${it.groupValues[2]}<br>"
            }
            // Double newlines → paragraph break
            .replace("\n\n", "<br><br>")
            // Single newlines → line break
            .replace("\n", "<br>")
            // Clean up excessive <br>
            .replace(Regex("""(<br>){3,}"""), "<br><br>")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    private fun startTypingAnimation(root: View) {
        val dot1 = root.findViewById<View>(R.id.dot1)
        val dot2 = root.findViewById<View>(R.id.dot2)
        val dot3 = root.findViewById<View>(R.id.dot3)

        fun pulseDot(dot: View, delay: Long): AnimatorSet {
            val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.4f, 1f).apply { duration = 600 }
            val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.4f, 1f).apply { duration = 600 }
            val alpha = ObjectAnimator.ofFloat(dot, "alpha", 0.4f, 1f, 0.4f).apply { duration = 600 }
            return AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                startDelay = delay
            }
        }

        val set = AnimatorSet().apply {
            playTogether(
                pulseDot(dot1, 0),
                pulseDot(dot2, 150),
                pulseDot(dot3, 300)
            )
        }

        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (dot1.isAttachedToWindow) {
                    set.start()
                }
            }
        })
        set.start()
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}