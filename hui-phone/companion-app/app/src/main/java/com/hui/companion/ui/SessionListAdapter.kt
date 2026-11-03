package com.hui.companion.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hui.companion.R
import com.hui.companion.db.ChatSessionSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Prompt-kit Sidebar history list (full-chat-app.tsx SidebarChatHistory):
 * SidebarContent > SidebarGroup (period) > SidebarGroupLabel (period) >
 * SidebarMenu > SidebarMenuButton (conversation title).
 */
sealed class SessionRow {
    data class Header(val label: String) : SessionRow()
    data class Session(val item: ChatSessionSummary) : SessionRow()
}

fun periodLabel(ts: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance()
    val d = Date(ts).let { Date(ts) }
    val today = Calendar.getInstance(); today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)
    val dayTs = today.timeInMillis
    val day = 24 * 60 * 60 * 1000L
    return when {
        ts >= dayTs -> "Today"
        ts >= dayTs - day -> "Yesterday"
        ts >= dayTs - 7 * day -> "Last 7 days"
        else -> "Last month"
    }
}

class SessionListAdapter(
    private val context: Context,
    private val onSessionClick: (String) -> Unit
) : ListAdapter<SessionRow, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<SessionRow>() {
        override fun areItemsTheSame(a: SessionRow, b: SessionRow) =
            a is SessionRow.Header && b is SessionRow.Header && a.label == b.label ||
            a is SessionRow.Session && b is SessionRow.Session && a.item.sessionId == b.item.sessionId
        override fun areContentsTheSame(a: SessionRow, b: SessionRow) = a == b
    }
) {
    var activeSessionId: String? = null

    fun setActive(id: String?) { activeSessionId = id; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is SessionRow.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(context)
        return if (viewType == 0) {
            val v = inf.inflate(R.layout.item_session_header, parent, false)
            HeaderHolder(v)
        } else {
            val v = inf.inflate(R.layout.item_session_row, parent, false)
            SessionHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SessionRow.Header -> (holder as HeaderHolder).bind(row.label)
            is SessionRow.Session -> (holder as SessionHolder).bind(row.item, row.item.sessionId == activeSessionId, onSessionClick)
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.sessionHeaderLabel)
        fun bind(text: String) { label.text = text }
    }

    class SessionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row: View = view.findViewById(R.id.sessionRow)
        private val title: TextView = view.findViewById(R.id.sessionTitle)
        private val preview: TextView = view.findViewById(R.id.sessionLastMessage)
        fun bind(item: ChatSessionSummary, active: Boolean, onClick: (String) -> Unit) {
            title.text = item.title
            preview.text = item.lastMessage
            row.alpha = if (active) 1f else 0.72f
            row.setOnClickListener { onClick(item.sessionId) }
        }
    }
}

/** Flat row list grouped by prompt-kit period labels. */
fun toSessionRows(sessions: List<ChatSessionSummary>, query: String = ""): List<SessionRow> {
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) sessions else sessions.filter {
        it.title.lowercase().contains(q) || it.lastMessage.lowercase().contains(q)
    }
    val out = mutableListOf<SessionRow>()
    val periods = listOf("Today", "Yesterday", "Last 7 days", "Last month")
    for (p in periods) {
        val group = filtered.filter { periodLabel(it.lastTs) == p }
        if (group.isNotEmpty()) {
            out += SessionRow.Header(p)
            out += group.map { SessionRow.Session(it) }
        }
    }
    return out
}
