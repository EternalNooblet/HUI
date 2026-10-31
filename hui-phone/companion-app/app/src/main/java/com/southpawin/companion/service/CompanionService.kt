package com.southpawin.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.southpawin.companion.MainActivity
import com.southpawin.companion.R
import com.southpawin.companion.util.Config
import com.southpawin.companion.detector.AttentionDetector
import com.southpawin.companion.detector.BatteryMonitor
import com.southpawin.companion.detector.ScreenMonitor
import com.southpawin.companion.event.Command
import com.southpawin.companion.event.Event
import com.southpawin.companion.event.EventBus
import com.southpawin.companion.transport.CommandExecutor
import com.southpawin.companion.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class CompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventBus = EventBus()
    private lateinit var transport: Transport
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var attentionDetector: AttentionDetector
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var screenMonitor: ScreenMonitor
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "companion_service"
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("CompanionService created")
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "companion:wake")
        wakeLock?.acquire()
        
        commandExecutor = CommandExecutor(this)
        transport = Transport(this, eventBus, commandExecutor)
        attentionDetector = AttentionDetector(this, eventBus)
        batteryMonitor = BatteryMonitor(this, eventBus)
        screenMonitor = ScreenMonitor(this, eventBus)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        
        startEventLoops()
        
        transport.connect()
        
        attentionDetector.start()
        batteryMonitor.start()
        screenMonitor.start()
    }
    
    private fun startEventLoops() {
        scope.launch {
            for (event in eventBus.events) {
                transport.send(event)
            }
        }
        
        scope.launch {
            for (command in eventBus.commands) {
                val result = commandExecutor.execute(command)
                Timber.d("Command executed: ${command.command} -> $result")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        Timber.i("CompanionService destroyed")
        attentionDetector.stop()
        batteryMonitor.stop()
        screenMonitor.stop()
        transport.disconnect()
        wakeLock?.release()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val color = when {
            status.contains("Connected") -> getColor(R.color.connected)
            status.contains("Connecting") -> getColor(R.color.connecting)
            else -> getColor(R.color.disconnected)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setColor(color)
            .setColorized(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    fun updateNotificationStatus(status: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(status))
    }
}