package com.hui.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Boot received: ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED 
            || intent.action == "android.intent.action.QUICKBOOT_POWERON"
            || intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val serviceIntent = Intent(context, CompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}