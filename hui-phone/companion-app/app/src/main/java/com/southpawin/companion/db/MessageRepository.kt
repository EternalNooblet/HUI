package com.southpawin.companion.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MessageRepository(private val dao: MessageDao) {

    suspend fun insertMessage(
        role: String,
        content: String,
        voiceSession: String? = null,
        toolCallsJson: String? = null,
        citationsJson: String? = null,
        reasoningJson: String? = null,
        messageType: String = "text",
        isStreaming: Boolean = false,
        streamMode: String = "typewriter",
        streamSpeed: Int = 20
    ) {
        withContext(Dispatchers.IO) {
            val entity = MessageEntity(
                role = role,
                content = content,
                voiceSession = voiceSession,
                toolCallsJson = toolCallsJson,
                citationsJson = citationsJson,
                reasoningJson = reasoningJson,
                isStreaming = isStreaming,
                streamMode = streamMode,
                streamSpeed = streamSpeed
            )
            dao.insert(entity)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clearAll() }

    suspend fun clearSession(voiceSession: String) = withContext(Dispatchers.IO) { dao.clearSession(voiceSession) }

    fun observeAllMessages(): Flow<List<MessageEntity>> = dao.getAllMessages()

    fun observeSessionMessages(voiceSession: String): Flow<List<MessageEntity>> = dao.getMessagesBySession(voiceSession)
}

class MessageRepositoryProvider {
    companion object {
        @Volatile
        private var instance: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository {
            return instance ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val repo = MessageRepository(db.messageDao())
                instance = repo
                repo
            }
        }
    }
}