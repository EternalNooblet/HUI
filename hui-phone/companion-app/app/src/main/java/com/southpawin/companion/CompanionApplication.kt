package com.southpawin.companion

import android.app.Application
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
    }
}