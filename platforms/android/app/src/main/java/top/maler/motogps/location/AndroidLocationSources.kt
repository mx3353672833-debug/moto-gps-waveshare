package top.maler.motogps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import top.maler.motogps.model.NavigationFix
import top.maler.motogps.model.WGS84Point
import top.maler.motogps.nav.NavigationLocationSource
import top.maler.motogps.nav.NavigationSourceException

private const val MAXIMUM_FIX_AGE_MS = 15_000L

private fun Location.ageMs(): Long =
    (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

private fun Location.isUsableFix(): Boolean =
    hasAccuracy() && accuracy >= 0 && latitude.isFinite() && longitude.isFinite() && ageMs() in -1_000..MAXIMUM_FIX_AGE_MS

fun Context.hasFineLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Context.hasAnyLocationPermission(): Boolean = hasFineLocationPermission() ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Navigation fixes from the platform LocationManager, so riders do not need
 * Google Play services. GPS_PROVIDER fixes are WGS84 by platform contract;
 * a network fix only bridges the gap until satellites are acquired.
 */
class AndroidNavigationLocationSource(private val context: Context) : NavigationLocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var onFix: ((NavigationFix) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null
    private var lastEmittedElapsedNanos = 0L
    private var lastSatelliteFixElapsedNanos = 0L
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) onFailure?.invoke("系统定位已关闭，请在设置中开启定位")
        }
        override fun onProviderEnabled(provider: String) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    override fun start(onFix: (NavigationFix) -> Unit, onFailure: (String) -> Unit) {
        stop()
        if (!context.hasFineLocationPermission()) {
            throw NavigationSourceException("需要“精确位置”权限才能导航，请在系统设置中允许定位")
        }
        if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw NavigationSourceException("系统定位已关闭，请在设置中开启定位")
        }
        this.onFix = onFix
        this.onFailure = onFailure
        running = true
        // No distance filter: with a 0 m filter fixes keep arriving while
        // parked, so the round-screen speed decays to zero.
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
        }
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
            handler.post { if (running) accept(last) }
        }
    }

    override fun stop() {
        running = false
        manager?.removeUpdates(listener)
        handler.removeCallbacksAndMessages(null)
        onFix = null
        onFailure = null
        lastEmittedElapsedNanos = 0L
        lastSatelliteFixElapsedNanos = 0L
    }

    private fun accept(location: Location) {
        if (!running || !location.isUsableFix()) return
        if (location.elapsedRealtimeNanos <= lastEmittedElapsedNanos) return
        val isSatellite = location.provider == LocationManager.GPS_PROVIDER
        if (isSatellite) {
            lastSatelliteFixElapsedNanos = location.elapsedRealtimeNanos
        } else if (lastSatelliteFixElapsedNanos != 0L &&
            location.elapsedRealtimeNanos - lastSatelliteFixElapsedNanos < 5_000_000_000L
        ) {
            return
        }
        lastEmittedElapsedNanos = location.elapsedRealtimeNanos
        onFix?.invoke(
            NavigationFix(
                coordinate = WGS84Point(longitudeDeg = location.longitude, latitudeDeg = location.latitude),
                altitudeM = if (location.hasAltitude()) location.altitude else null,
                horizontalAccuracyM = location.accuracy.toDouble(),
                speedMps = if (location.hasSpeed() && location.speed >= 0) location.speed.toDouble() else null,
                courseDeg = if (location.hasBearing() && location.bearing >= 0) location.bearing.toDouble() else null,
                timestampMs = location.time,
            ),
        )
    }
}

enum class SearchLocationStatus { PREPARING, AVAILABLE, PERMISSION_DENIED, UNAVAILABLE }

/**
 * One coarse, recent point for POI ranking and the route-preview origin. Real
 * navigation keeps its separate continuous source and foreground service.
 */
class SearchLocationBiasSource(private val context: Context) {
    var onLocationChange: ((WGS84Point?) -> Unit)? = null
    var onStatusChange: ((SearchLocationStatus) -> Unit)? = null

    var latestPoint: WGS84Point? = null
        private set
    var status: SearchLocationStatus = SearchLocationStatus.PREPARING
        private set

    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private val activeRequests = mutableListOf<() -> Unit>()

    fun prepare() {
        if (!context.hasAnyLocationPermission()) {
            publish(null, SearchLocationStatus.PERMISSION_DENIED)
            return
        }
        if (manager == null || enabledProviders().isEmpty()) {
            publish(null, SearchLocationStatus.UNAVAILABLE)
            return
        }
        setStatus(SearchLocationStatus.PREPARING)
        request(preferSatellite = false)
    }

    fun refresh() {
        if (!context.hasAnyLocationPermission()) return
        if (latestPoint == null) setStatus(SearchLocationStatus.PREPARING)
        request(preferSatellite = false)
    }

    /** Route alternatives must start where the rider is now, not at the search bias. */
    fun refreshForRoutePlanning(): Boolean {
        if (!context.hasAnyLocationPermission() || manager == null || enabledProviders().isEmpty()) return false
        latestPoint = null
        setStatus(SearchLocationStatus.PREPARING)
        request(preferSatellite = true)
        return true
    }

    fun cancel() {
        generation += 1
        activeRequests.forEach { it() }
        activeRequests.clear()
        handler.removeCallbacksAndMessages(null)
    }

    private fun enabledProviders(): List<String> = listOfNotNull(
        LocationManager.NETWORK_PROVIDER.takeIf { manager?.isProviderEnabled(it) == true },
        LocationManager.GPS_PROVIDER.takeIf {
            context.hasFineLocationPermission() && manager?.isProviderEnabled(it) == true
        },
    )

    @SuppressLint("MissingPermission")
    private fun request(preferSatellite: Boolean) {
        val locationManager = manager ?: return
        cancel()
        val current = generation
        var pending = 0
        var delivered = false

        fun finish(location: Location?) {
            if (current != generation || delivered) return
            if (location != null && location.isUsableFix() && location.accuracy <= 2_000) {
                delivered = true
                cancel()
                publish(WGS84Point(longitudeDeg = location.longitude, latitudeDeg = location.latitude), SearchLocationStatus.AVAILABLE)
            } else {
                pending -= 1
                if (pending <= 0) publish(null, SearchLocationStatus.UNAVAILABLE)
            }
        }

        if (!preferSatellite) {
            // A fresh cached network fix is good enough to rank search results.
            enabledProviders().mapNotNull { locationManager.getLastKnownLocation(it) }
                .filter { it.isUsableFix() && it.accuracy <= 2_000 }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { cached ->
                    publish(WGS84Point(longitudeDeg = cached.longitude, latitudeDeg = cached.latitude), SearchLocationStatus.AVAILABLE)
                    return
                }
        }

        val providers = enabledProviders()
        pending = providers.size
        if (providers.isEmpty()) {
            publish(null, SearchLocationStatus.UNAVAILABLE)
            return
        }
        for (provider in providers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                activeRequests += { signal.cancel() }
                locationManager.getCurrentLocation(provider, signal, context.mainExecutor) { finish(it) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        finish(location)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = finish(null)
                }
                activeRequests += { locationManager.removeUpdates(listener) }
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                handler.postDelayed({
                    locationManager.removeUpdates(listener)
                    finish(null)
                }, 30_000)
            }
        }
    }

    private fun setStatus(newStatus: SearchLocationStatus) {
        if (status == newStatus) return
        status = newStatus
        onStatusChange?.invoke(newStatus)
    }

    private fun publish(point: WGS84Point?, newStatus: SearchLocationStatus) {
        latestPoint = point
        setStatus(newStatus)
        onLocationChange?.invoke(point)
    }
}
