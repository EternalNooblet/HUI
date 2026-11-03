package com.southpawin.companion.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One row per chat session for the prompt-kit Sidebar history list. */
data class ChatSessionSummary(
    val sessionId: String,
    val title: String,
    val lastMessage: String,
    val lastTs: Long,
    val messageCount: Int
)

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

    /** All sessions, newest activity first. Title = first user message
     *  (prompt-kit conversation title); lastMessage = latest content. */
    fun observeSessions(): Flow<List<ChatSessionSummary>> =
        dao.getAllMessages().map { all ->
            all.filter { !it.voiceSession.isNullOrEmpty() }
                .groupBy { it.voiceSession!! }
                .map { (sid, msgs) ->
                    val sorted = msgs.sortedBy { it.timestamp }
                    val firstUser = sorted.firstOrNull { it.role == "user" }?.content?.trim() ?: ""
                    val title = if (firstUser.isNotEmpty())
                        firstUser.take(48) else "New chat"
                    ChatSessionSummary(
                        sessionId = sid,
                        title = title,
                        lastMessage = sorted.lastOrNull()?.content?.trim().orEmpty().take(80),
                        lastTs = sorted.lastOrNull()?.timestamp ?: 0L,
                        messageCount = sorted.size
                    )
                }
                .sortedByDescending { it.lastTs }
        }
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