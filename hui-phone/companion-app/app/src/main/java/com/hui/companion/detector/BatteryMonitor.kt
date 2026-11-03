package com.hui.companion.detector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.hui.companion.util.Config
import com.hui.companion.event.BatteryEvent
import com.hui.companion.event.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

class BatteryMonitor(
    private val context: Context,
    private val eventBus: EventBus
) {
    private var job: Job? = null
    private var lastLevel = -1f
    
    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            reportBattery(true)
            while (true) {
                delay(Config.batteryReportIntervalS * 1000L)
                reportBattery()
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, filter)
    }
    
    fun stop() {
        job?.cancel()
        context.unregisterReceiver(batteryReceiver)
    }
    
    private fun reportBattery(force: Boolean = false) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = level.toFloat() / scale
            
            if (force || Math.abs(batteryPct - lastLevel) > 0.01f || lastLevel < 0) {
                lastLevel = batteryPct
                
                val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                }
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                
                val event = BatteryEvent(
                    BatteryEvent.BatteryPayload(
                        level = batteryPct,
                        charging = charging,
                        health = health,
                        temperatureC = temp
                    )
                )
                Timber.d("Posting battery event: level=$batteryPct, charging=$charging")
                eventBus.tryPost(event)
            }
        }
    }
    
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reportBattery()
        }
    }
}