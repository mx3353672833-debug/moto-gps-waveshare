package com.motogps.android

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class MotoGpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        } catch (_: Exception) {}
    }
}
