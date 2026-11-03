package com.hui.companion.detector

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.hui.companion.util.Config
import com.hui.companion.event.ScreenEvent
import com.hui.companion.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

class ScreenMonitor(
    private val context: Context,
    private val eventBus: EventBus
) {
    private var job: Job? = null
    private var lastState = ""
    
    fun start() {
        job = CoroutineScope(Dispatchers.Main).launch {
            reportScreen()
            while (true) {
                delay(5000)
                reportScreen()
            }
        }
    }
    
    fun stop() {
        job?.cancel()
    }
    
    private fun reportScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val state = if (Build.VERSION.SDK_INT >= 20) {
            if (!pm.isInteractive) "OFF" else "ON_LOCKED"
        } else {
            val brightness = try {
                android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (e: Exception) { 255 }
            if (brightness == 0) "OFF" else "ON_LOCKED"
        }
        
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val finalState = when {
            state == "OFF" -> "OFF"
            keyguard.isDeviceLocked -> "ON_LOCKED"
            else -> "UNLOCKED"
        }
        
        if (finalState != lastState) {
            lastState = finalState
            val brightness = try {
                android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (e: Exception) { 1.0f }
            
            val event = ScreenEvent(
                ScreenEvent.ScreenPayload(
                    state = finalState,
                    brightness = brightness
                )
            )
            eventBus.tryPost(event)
            Timber.d("Screen state: $finalState")
        }
    }
}