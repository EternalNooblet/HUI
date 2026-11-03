package com.hui.companion.voice

data class ChatMessage(
    val id: Long = 0,
    val role: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val voiceSession: String? = null
)