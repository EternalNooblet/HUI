package com.southpawin.companion.voice

enum class TalkMode {
    VOICE, CHAT
}

enum class ConnectionState {
    CONNECTED, CONNECTING, DISCONNECTED
}

typealias OnModeChangeListener = (TalkMode) -> Unit