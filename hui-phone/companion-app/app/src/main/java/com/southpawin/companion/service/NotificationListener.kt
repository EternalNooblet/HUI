package com.southpawin.companion.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.southpawin.companion.util.Config
import com.southpawin.companion.event.EventBus
import com.southpawin.companion.event.NotificationEvent
import timber.log.Timber

class NotificationListener : NotificationListenerService() {
    private lateinit var eventBus: EventBus
    
    override fun onCreate() {
        super.onCreate()
        eventBus = EventBus()
        Timber.d("NotificationListener created")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldForward(sbn)) return
        
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: sbn.packageName
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() 
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        
        val priority = when (notification.priority) {
            Notification.PRIORITY_MAX -> "MAX"
            Notification.PRIORITY_HIGH -> "HIGH"
            Notification.PRIORITY_DEFAULT -> "DEFAULT"
            Notification.PRIORITY_LOW -> "LOW"
            Notification.PRIORITY_MIN -> "MIN"
            else -> "UNKNOWN"
        }
        
        val category = notification.category ?: "uncategorized"
        val actions = notification.actions.map { it.title.toString() }.toList()
        
        val event = NotificationEvent(
            NotificationEvent.NotificationPayload(
                packageName = sbn.packageName,
                title = title,
                text = text,
                priority = priority,
                category = category,
                actions = actions
            )
        )
        eventBus.tryPost(event)
        Timber.d("Notification from ${sbn.packageName}: $title")
    }
    
    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        
        if (!NotificationManagerCompat.getEnabledListenerPackages(this)
                .any { it == packageName }) {
            return false
        }
        
        if (notification.priority < Notification.PRIORITY_DEFAULT) return false
        
        return Config.notificationFilterPackages.contains(sbn.packageName)
    }
}