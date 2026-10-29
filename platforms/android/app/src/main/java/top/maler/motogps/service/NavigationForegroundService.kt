package top.maler.motogps.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.maler.motogps.MainActivity
import top.maler.motogps.MotoApplication
import top.maler.motogps.R
import top.maler.motogps.app.AppModel
import top.maler.motogps.ble.hasBluetoothPermissions
import top.maler.motogps.location.hasFineLocationPermission

/**
 * Started from the foreground when the rider taps “开始导航”. It keeps the
 * process (location updates and the round-screen GATT link) alive with the
 * screen off, shows a persistent notification with an end action, and holds a
 * partial wake lock only for the duration of the ride.
 */
class NavigationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updates: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val model: AppModel get() = (application as MotoApplication).model

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            model.stopNavigation()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (!model.isNavigationActive) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val types = serviceTypes(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } catch (_: Exception) {
            // Android refuses a foreground service whose permissions are gone;
            // navigation keeps running while the app stays visible.
            stopSelfSafely()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        updates?.cancel()
        updates = scope.launch {
            snapshotFlow { notificationText(model) to model.isNavigationActive }
                .distinctUntilChanged()
                .collectLatest { (_, active) ->
                    if (!active) {
                        stopSelfSafely()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                    }
                }
        }
        // A killed ride must not silently resume with stale state.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updates?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        // Bounded so a crash in the ride lifecycle can never drain the battery for good.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoGPS:navigation").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1_000L)
        }
    }

    private fun buildNotification(): Notification {
        val (title, text) = notificationText(model)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationForegroundService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "结束导航", end)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1_001
        private const val ACTION_END = "top.maler.motogps.action.END_NAVIGATION"

        fun start(context: Context) {
            ensureChannel(context)
            if (serviceTypes(context) == 0) return
            ContextCompat.startForegroundService(context, Intent(context, NavigationForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }

        private fun serviceTypes(context: Context): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
            var types = 0
            if (context.hasFineLocationPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context.hasBluetoothPermissions()) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            return types
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_navigation),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_navigation_description) }
            manager.createNotificationChannel(channel)
        }

        private fun notificationText(model: AppModel): Pair<String, String> {
            val title = if (model.isDemoActive) "演示导航 · ${model.activeDestinationName}" else "前往 ${model.activeDestinationName}"
            val nav = model.navigation
            val body = when (nav.stateName) {
                "navigating", "rerouting" -> buildString {
                    if (nav.instructionText.isNotEmpty()) append(nav.instructionText).append(" · ")
                    append("剩余 ${model.remainingDistanceText} · ${model.remainingDurationText}")
                    append(if (model.deviceReady) " · 圆屏已连接" else " · 圆屏未连接")
                }
                "arrived" -> "已到达目的地"
                "planning" -> "正在规划路线"
                else -> "正在获取位置"
            }
            return title to body
        }
    }
}
