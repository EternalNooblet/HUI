package com.hui.companion.event

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import timber.log.Timber

// Base event class
sealed class Event(
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: String = java.time.Instant.now().toString(),
    @SerializedName("device_id") val deviceId: String = ""
) {
    fun toJson(): String = Gson().toJson(this)
}

// Attention event (primary wake signal)
data class AttentionEvent(
    @SerializedName("payload") val payload: AttentionPayload
) : Event("attention") {
    data class AttentionPayload(
        @SerializedName("addressing") val addressing: Boolean,
        @SerializedName("looking") val looking: Boolean,
        @SerializedName("speaking") val speaking: Boolean,
        @SerializedName("confidence") val confidence: Float,
        @SerializedName("gaze_vector") val gazeVector: FloatArray? = null,
        @SerializedName("audio_level_db") val audioLevelDb: Float? = null
    )
}

// Battery event
data class BatteryEvent(
    @SerializedName("payload") val payload: BatteryPayload
) : Event("battery") {
    data class BatteryPayload(
        @SerializedName("level") val level: Float,
        @SerializedName("charging") val charging: Boolean,
        @SerializedName("health") val health: String,
        @SerializedName("temperature_c") val temperatureC: Float
    )
}

// Notification event
data class NotificationEvent(
    @SerializedName("payload") val payload: NotificationPayload
) : Event("notification") {
    data class NotificationPayload(
        @SerializedName("package") val packageName: String,
        @SerializedName("title") val title: String,
        @SerializedName("text") val text: String,
        @SerializedName("priority") val priority: String,
        @SerializedName("category") val category: String,
        @SerializedName("actions") val actions: List<String> = emptyList()
    )
}

// Screen state event
data class ScreenEvent(
    @SerializedName("payload") val payload: ScreenPayload
) : Event("screen") {
    data class ScreenPayload(
        @SerializedName("state") val state: String,  // OFF | ON_LOCKED | UNLOCKED
        @SerializedName("brightness") val brightness: Float
    )
}

// Audio chunk for STT
data class AudioEvent(
    @SerializedName("payload") val payload: AudioPayload
) : Event("audio") {
    data class AudioPayload(
        @SerializedName("format") val format: String = "pcm16",
        @SerializedName("sample_rate") val sampleRate: Int = 16000,
        @SerializedName("channels") val channels: Int = 1,
        @SerializedName("duration_ms") val durationMs: Int,
        @SerializedName("data_b64") val dataBase64: String,
        @SerializedName("sequence") val sequence: Long,
        @SerializedName("voice_session") val voiceSession: String? = null
    )
}

// Command from server
sealed class Command(
    @SerializedName("type") open val type: String = "command",
    @SerializedName("command") open val command: String,
    @SerializedName("request_id") open val requestId: String,
    @SerializedName("payload") open val payload: CommandPayload
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun parse(json: String): Command? {
            val jsonObject = com.google.gson.JsonParser.parseString(json).asJsonObject
            val messageType = jsonObject.get("type").asString
            
            // Handle welcome message from server (not a command)
            if (messageType == "welcome") {
                Timber.i("Received welcome message from server")
                return null
            }
            
            // Handle command messages
            val commandType = jsonObject.get("command")?.asString
            val requestId = jsonObject.get("request_id")?.asString
            if (commandType == null || requestId == null) {
                Timber.w("Invalid command message: missing command or request_id")
                return null
            }
            
            val payload = if (jsonObject.has("payload")) {
                Gson().fromJson(jsonObject.get("payload"), CommandPayload::class.java)
            } else {
                CommandPayload()
            }
            return when (commandType) {
                "speak" -> SpeakCommand(requestId, payload)
                "screen_action" -> ScreenActionCommand(requestId, payload)
                "launch_app" -> LaunchAppCommand(requestId, payload)
                "config" -> ConfigCommand(requestId, payload)
                "voice_state" -> VoiceStateCommand(requestId, payload)
                "chat_reply" -> ChatReplyCommand(requestId, payload)
                else -> UnknownCommand(requestId, commandType, payload)
            }
        }
    }
}

data class SpeakCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "speak", requestId = requestId, payload = payload)

data class ScreenActionCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "screen_action", requestId = requestId, payload = payload)

data class LaunchAppCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "launch_app", requestId = requestId, payload = payload)

data class ConfigCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "config", requestId = requestId, payload = payload)

data class VoiceStateCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "voice_state", requestId = requestId, payload = payload)

// Chat reply command from server (text bubble)
data class ChatReplyCommand(
    @SerializedName("request_id") override val requestId: String,
    override val payload: CommandPayload
) : Command(command = "chat_reply", requestId = requestId, payload = payload)

data class UnknownCommand(
    @SerializedName("request_id") override val requestId: String,
    @SerializedName("command") val originalCommand: String,
    override val payload: CommandPayload
) : Command(command = originalCommand, requestId = requestId, payload = payload)

data class CommandPayload(
    @SerializedName("text") val text: String? = null,
    @SerializedName("voice") val voice: String? = null,
    @SerializedName("pitch") val pitch: Float? = null,
    @SerializedName("priority") val priority: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("package") val packageName: String? = null,
    @SerializedName("intent_extras") val intentExtras: Map<String, String>? = null,
    @SerializedName("gaze_check_interval_ms") val gazeCheckIntervalMs: Long? = null,
    @SerializedName("audio_chunk_ms") val audioChunkMs: Int? = null,
    @SerializedName("battery_report_interval_s") val batteryReportIntervalS: Long? = null,
    @SerializedName("voice_session") val voiceSession: String? = null
)

// EventBus for internal communication
class EventBus {
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    
    val events: ReceiveChannel<Event> = eventChannel
    val commands: ReceiveChannel<Command> = commandChannel
    
    suspend fun post(event: Event) = eventChannel.send(event)
    suspend fun postCommand(command: Command) = commandChannel.send(command)
    
    fun tryPost(event: Event) = eventChannel.trySend(event)
    fun tryPostCommand(command: Command) = commandChannel.trySend(command)
    
    fun close() {
        eventChannel.close()
        commandChannel.close()
    }
}

// Result types for command execution
// Voice events
data class VoiceStartEvent(
    @SerializedName("payload") val payload: VoiceStartPayload
) : Event("voice_start") {
    data class VoiceStartPayload(
        @SerializedName("voice_session") val voiceSession: String,
        @SerializedName("format") val format: String = "pcm16",
        @SerializedName("sample_rate") val sampleRate: Int = 16000,
        @SerializedName("channels") val channels: Int = 1
    )
}

data class VoiceSendEvent(
    @SerializedName("payload") val payload: VoiceSendPayload
) : Event("voice_send") {
    data class VoiceSendPayload(
        @SerializedName("voice_session") val voiceSession: String,
        @SerializedName("last_sequence") val lastSequence: Long,
        @SerializedName("duration_ms") val durationMs: Long
    )
}

data class VoiceCancelEvent(
    @SerializedName("payload") val payload: VoiceCancelPayload
) : Event("voice_cancel") {
    data class VoiceCancelPayload(
        @SerializedName("voice_session") val voiceSession: String
    )
}

// Chat event (text message from user)
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

sealed class Result {
    data class Success(val data: String? = null) : Result()
    data class Failure(val error: String) : Result()
    object UnknownCommand : Result()
}