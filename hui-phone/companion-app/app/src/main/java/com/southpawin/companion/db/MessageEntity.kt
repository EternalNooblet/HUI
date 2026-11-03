package com.southpawin.companion.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,                    // "user" | "assistant" | "system" | "tool"
    val content: String,                 // markdown content (prompt-kit: children string)
    val timestamp: Long = System.currentTimeMillis(),
    val voiceSession: String? = null,

    // Tool calls (prompt-kit ToolPart[])
    val toolCallsJson: String? = null,   // List<ToolPart> JSON

    // Sources (prompt-kit Source[])
    val citationsJson: String? = null,   // List<Citation> JSON

    // Reasoning (prompt-kit ChainOfThought steps)
    val reasoningJson: String? = null,   // List<ReasoningStep> JSON

    // Streaming state
    val isStreaming: Boolean = false,
    val streamMode: String = "typewriter", // "typewriter" | "fade"
    val streamSpeed: Int = 20            // 1-100
)