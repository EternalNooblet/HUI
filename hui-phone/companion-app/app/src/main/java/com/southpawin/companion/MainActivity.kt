package com.southpawin.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.southpawin.companion.util.Config
import com.southpawin.companion.service.CompanionService
import com.southpawin.companion.BuildConfig
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var buildNumberText: TextView
    private lateinit var connectButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        deviceIdText = findViewById(R.id.deviceIdText)
        serverUrlText = findViewById(R.id.serverUrlText)
        buildNumberText = findViewById(R.id.buildNumberText)
        connectButton = findViewById(R.id.connectButton)
        
        updateUI()
        
        connectButton.setOnClickListener {
            requestPermissionsAndStart()
        }
    }
    
    private fun updateUI() {
        deviceIdText.text = "Device ID: ${Config.deviceId}"
        serverUrlText.text = "Server: ${if (Config.serverUrl.isBlank()) "Not configured" else Config.serverUrl}"
        buildNumberText.text = "Build: ${BuildConfig.BUILD_CODENAME}-${BuildConfig.BUILD_NUMBER}"
        
        val isRunning = isServiceRunning()
        statusText.text = if (isRunning) "Status: Running" else "Status: Stopped"
        statusText.setTextColor(
            if (isRunning) getColor(R.color.connected) else getColor(R.color.disconnected)
        )
    }
    
    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == CompanionService::class.java.name }
    }
    
    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        } else {
            startServiceAndConfigure()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startServiceAndConfigure()
        } else {
            Timber.w("Permissions denied")
        }
    }
    
    private fun startServiceAndConfigure() {
        val intent = Intent(this, CompanionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        val notificationIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        startActivity(notificationIntent)
        
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        
        updateUI()
    }
}