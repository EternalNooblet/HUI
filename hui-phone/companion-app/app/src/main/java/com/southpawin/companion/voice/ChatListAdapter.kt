package com.southpawin.companion.voice

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.southpawin.companion.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatListAdapter(
    private val context: Context
) : ListAdapter<ChatMessage, ChatListAdapter.MessageViewHolder>(ChatMessageDiffCallback()) {

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubbleContainer: LinearLayout = view.findViewById(R.id.bubbleContainer)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        val isUser = message.role == "user"

        holder.messageText.text = message.text

        // Style the bubble based on role
        val bubbleBg = if (isUser) R.drawable.bubble_user else R.drawable.bubble_assistant
        holder.bubbleContainer.setBackgroundResource(bubbleBg)

        // Align: user messages right, assistant left
        val lp = FrameLayout.LayoutParams(
            holder.bubbleContainer.layoutParams.width,
            holder.bubbleContainer.layoutParams.height
        )
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        // Vertical spacing (px, user-specified): 2px same-author,
        // 4px cross-author. Top margin on each item except the first row.
        val dens = holder.itemView.resources.displayMetrics.density
        lp.topMargin = when {
            position == 0 -> (8 * dens).toInt()
            getItem(position - 1).role == message.role -> (2 * dens).toInt()
            else -> (4 * dens).toInt()
        }
        lp.bottomMargin = (2 * dens).toInt()
        holder.bubbleContainer.layoutParams = lp

        // Text color
        holder.messageText.setTextColor(
            ContextCompat.getColor(context, if (isUser) R.color.text_on_primary else R.color.text_primary)
        )
        // Timestamp per-bubble (WCAG AA). User bubble #1D4ED8 takes
        // timestamp_user #DBEAFE (5.49:1); assistant #1E1E1E takes
        // timestamp_assistant #A1A1AA (6.50:1). No alpha — alpha was the bug.
        holder.timestampText.setTextColor(
            ContextCompat.getColor(
                context,
                if (isUser) R.color.timestamp_user else R.color.timestamp_assistant
            )
        )
        holder.timestampText.alpha = 1f

        // Timestamp
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.timestampText.text = sdf.format(Date(message.timestamp))
    }
}

class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.role == newItem.role &&
               oldItem.text == newItem.text &&
               oldItem.timestamp == newItem.timestamp
    }
}