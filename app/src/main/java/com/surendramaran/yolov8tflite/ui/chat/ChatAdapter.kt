package com.surendramaran.yolov8tflite.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.surendramaran.yolov8tflite.R

data class ChatMessage(val text: String, val isUser: Boolean)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            val lastMsg = messages[lastIndex]
            messages[lastIndex] = lastMsg.copy(text = text)
            notifyItemChanged(lastIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        val tvMessage = holder.itemView.findViewById<TextView>(R.id.tvMessage)
        val tvSender = holder.itemView.findViewById<TextView>(R.id.tvSender)
        val layout = holder.itemView as LinearLayout

        tvMessage.text = message.text

        if (message.isUser) {
            layout.gravity = Gravity.END
            tvSender.text = holder.itemView.context.getString(R.string.you)
            tvSender.gravity = Gravity.END
            tvMessage.setBackgroundColor(0xFF007AFF.toInt()) // Blue
        } else {
            layout.gravity = Gravity.START
            tvSender.text = holder.itemView.context.getString(R.string.fish_ai)
            tvSender.gravity = Gravity.START
            tvMessage.setBackgroundColor(0xFF555555.toInt()) // Gray
        }
    }

    override fun getItemCount() = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}