package com.motogps.android.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LocationForegroundService : Service(), LocationListener {
    companion object {
        const val CHANNEL_ID = "MotoGPS_Location_Channel"
        const val NOTIFICATION_ID = 1001
        var onLocationUpdate: ((Double, Double) -> Unit)? = null
    }

    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moto GPS")
            .setContentText("Servizio di localizzazione attivo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLast = selectBestLocation(lastGps, lastNet)
            if (bestLast != null) {
                onLocationUpdate?.invoke(bestLast.latitude, bestLast.longitude)
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            }
        } catch (_: Exception) {}
    }

    private fun selectBestLocation(loc1: Location?, loc2: Location?): Location? {
        if (loc1 == null) return loc2
        if (loc2 == null) return loc1
        return if (loc1.time >= loc2.time) loc1 else loc2
    }

    override fun onLocationChanged(location: Location) {
        onLocationUpdate?.invoke(location.latitude, location.longitude)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Moto GPS Navigation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
