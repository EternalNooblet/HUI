package com.hui.companion.event

import com.google.gson.annotations.SerializedName

/**
 * Direct mode (DIRECT_PLAN.md): the only client→server "event" left is a
 * chat turn, and even that now goes straight through Transport.submitPrompt.
 * The old :8081 event/command surface (attention/battery/screen/notification/
 * audio chunks, hello/welcome, speak/chat_reply/voice_state commands,
 * EventBus) is deleted — the gateway speaks JSON-RPC, answered via
 * message.* events and srq-… server requests.
 */
sealed class Event(
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: String = java.time.Instant.now().toString(),
    @SerializedName("device_id") val deviceId: String = ""
)

// Chat turn (text message from user). Kept for Transport.send compat;
// VoiceUi now calls Transport.submitPrompt directly.
data class ChatEvent(
    @SerializedName("payload") val payload: ChatPayload
) : Event("chat") {
    data class ChatPayload(
        @SerializedName("text") val text: String,
        @SerializedName("voice_session") val voiceSession: String? = null,
        @SerializedName("image_url") val imageUrl: String? = null,
        @SerializedName("image_name") val imageName: String? = null
    )
}
