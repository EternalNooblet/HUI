package com.southpawin.companion.detector

import android.content.Context
import com.southpawin.companion.util.Config
import com.southpawin.companion.event.AttentionEvent
import com.southpawin.companion.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

class AttentionDetector(
    private val context: Context,
    private val eventBus: EventBus
) {
    private var job: Job? = null
    
    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(Config.gazeCheckIntervalMs)
                Timber.d("AttentionDetector tick (stub)")
            }
        }
    }
    
    fun stop() {
        job?.cancel()
    }
    
    fun simulateAttention() {
        val event = AttentionEvent(
            AttentionEvent.AttentionPayload(
                addressing = true,
                looking = true,
                speaking = true,
                confidence = 0.95f
            )
        )
        eventBus.tryPost(event)
        Timber.i("Simulated attention event sent")
    }
}