package com.southpawin.companion

import android.app.Application
import com.southpawin.companion.db.AppDatabase
import com.southpawin.companion.util.CompanionAppContext
import timber.log.Timber

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CompanionAppContext.instance = this
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(Timber.DebugTree())
        }
        // Pre-initialize Room database
        AppDatabase.getInstance(this)
        // Load persisted auth so QR-scanned connection details survive restart
        try {
            com.southpawin.companion.util.Config.loadVoice()
            com.southpawin.companion.util.Config.loadAuthToken()
            com.southpawin.companion.util.Config.loadServerUrl()
        } catch (e: Exception) {
            Timber.w("Could not load persisted config on startup", e)
        }
    }
}