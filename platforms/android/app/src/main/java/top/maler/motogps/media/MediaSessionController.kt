package top.maler.motogps.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import top.maler.motogps.ble.BleCommandDisposition
import top.maler.motogps.core.BleMediaStateInput

/**
 * Only exists so the user can grant notification access, which is what
 * Android requires before an app may read and control other apps' media
 * sessions. The app never reads notification content.
 */
class MediaNotificationListener : NotificationListenerService()

/**
 * Projects the active Android media session onto the round screen and runs
 * its previous / play-pause / next commands. Uses the public
 * MediaSessionManager API with user-granted notification access; never an
 * accessibility service. Players that expose no media session cannot be
 * controlled, and "like" is reported unavailable rather than faked.
 */
class MediaSessionController(private val context: Context) {
    var onStateChange: ((BleMediaStateInput) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
    private var controller: MediaController? = null
    private var started = false
    private var listening = false

    val hasAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handler.post { selectController(controllers.orEmpty()) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishCurrentState()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishCurrentState()
        override fun onSessionDestroyed() {
            handler.post { refreshSessions() }
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            publishCurrentState()
        }
    }

    fun start() {
        started = true
        refreshSessions()
    }

    /** Re-evaluates access, e.g. after the user returns from system settings. */
    fun refreshSessions() {
        if (!started) return
        if (!hasAccess) {
            stopListening()
            publishCurrentState()
            return
        }
        try {
            if (!listening) {
                sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                listening = true
            }
            selectController(sessionManager?.getActiveSessions(listenerComponent).orEmpty())
        } catch (_: SecurityException) {
            stopListening()
            publishCurrentState()
        }
    }

    fun handleDeviceCommand(kind: Int): BleCommandDisposition {
        val current = controller
        if (!hasAccess || current == null) {
            publishCurrentState()
            return BleCommandDisposition.INVALID_STATE
        }
        val controls = current.transportControls
        when (kind) {
            16 -> controls.skipToPrevious()
            17 -> if (isPlaying(current.playbackState)) controls.pause() else controls.play()
            18 -> controls.skipToNext()
            else -> return BleCommandDisposition.UNSUPPORTED
        }
        // Players apply transport changes asynchronously: send an immediate and a settled projection.
        publishCurrentState()
        handler.postDelayed({ publishCurrentState() }, 350)
        return BleCommandDisposition.ACCEPTED
    }

    private fun stopListening() {
        if (listening) {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            listening = false
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun selectController(controllers: List<MediaController>) {
        // Prefer the session that is actually playing; otherwise the most recent one.
        val chosen = controllers.firstOrNull { isPlaying(it.playbackState) } ?: controllers.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = chosen
            chosen?.registerCallback(controllerCallback, handler)
        }
        publishCurrentState()
    }

    private fun publishCurrentState() {
        handler.removeCallbacks(progressTick)
        val current = controller
        if (!hasAccess) {
            onStateChange?.invoke(disconnected("需要通知使用权"))
            return
        }
        if (current == null) {
            onStateChange?.invoke(disconnected("尚未播放"))
            return
        }
        val metadata = current.metadata
        val state = current.playbackState
        val playing = isPlaying(state)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        onStateChange?.invoke(
            BleMediaStateInput(
                connected = true,
                playing = playing,
                likeAvailable = false,
                liked = false,
                trackToken = trackToken(current.packageName, title, artist, durationMs),
                positionSeconds = clampedSeconds(positionMs(state)),
                durationSeconds = clampedSeconds(durationMs),
                sourceName = sourceName(current.packageName),
                trackTitle = title.ifEmpty { "尚未播放" },
                artistName = artist,
            ),
        )
        // Progress is sent at most once per second while playing.
        if (playing) handler.postDelayed(progressTick, 1_000)
    }

    private fun disconnected(title: String) = BleMediaStateInput(
        connected = false,
        playing = false,
        likeAvailable = false,
        liked = false,
        trackToken = 0,
        positionSeconds = 0,
        durationSeconds = 0,
        sourceName = "音乐",
        trackTitle = title,
        artistName = "",
    )

    private fun sourceName(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        fun isPlaying(state: PlaybackState?): Boolean = when (state?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            -> true
            else -> false
        }

        fun positionMs(state: PlaybackState?): Long {
            state ?: return 0
            if (state.position < 0) return 0
            if (!isPlaying(state) || state.lastPositionUpdateTime <= 0) return state.position
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            return state.position + (elapsed * state.playbackSpeed).toLong()
        }

        fun clampedSeconds(milliseconds: Long): Int = (milliseconds / 1_000).coerceIn(0, 65_535).toInt()

        /** Session-local track identity: changes whenever the track changes. */
        fun trackToken(packageName: String, title: String, artist: String, durationMs: Long): Long {
            var hash = 2_166_136_261L
            for (byte in "$packageName\u0000$title\u0000$artist\u0000$durationMs".toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toLong() and 0xFF)) * 16_777_619L and 0xFFFF_FFFFL
            }
            return if (hash == 0L) 1L else hash
        }
    }
}
