package com.hui.companion

import android.app.Application
import com.hui.companion.db.AppDatabase
import com.hui.companion.util.CompanionAppContext
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
            com.hui.companion.util.Config.loadVoice()
            com.hui.companion.util.Config.loadAuthToken()
            com.hui.companion.util.Config.loadServerUrl()
            com.hui.companion.util.Config.loadCertFingerprint()
        } catch (e: Exception) {
            Timber.w("Could not load persisted config on startup", e)
        }
    }
}