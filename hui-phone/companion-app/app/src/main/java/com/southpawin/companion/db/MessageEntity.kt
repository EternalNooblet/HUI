package com.southpawin.companion.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,  // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val voiceSession: String? = null
)