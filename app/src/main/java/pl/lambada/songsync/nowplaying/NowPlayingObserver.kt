package pl.lambada.songsync.nowplaying

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper

/** What another app is currently playing, as shown on the Home card. */
data class NowPlayingTrack(
    val title: String,
    val artist: String?,
    val album: String?,
    /** Track length reported by the player, or null when it doesn't say (live streams, some apps). */
    val durationMs: Long?,
    val packageName: String,
    /** Human-readable name of the playing app ("Spotify"), null when the app isn't visible to us. */
    val appLabel: String?,
    val artwork: Bitmap?,
)

/**
 * Follows the active media sessions of other apps and reports the one that is playing (or null). Only works while
 * this app is an enabled notification listener; [start] returns false otherwise. Must be used from the main
 * thread: listener and controller callbacks are delivered on the main looper.
 */
class NowPlayingObserver(
    context: Context,
    private val onTrackChanged: (NowPlayingTrack?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
    }

    private var watched: List<Pair<MediaController, MediaController.Callback>> = emptyList()
    private var started = false

    /** Identity of the last published track, so position/state ticks don't churn the UI. */
    private var lastKey: List<Any?>? = null

    /** @return false when notification access isn't granted (Android refuses with a SecurityException). */
    fun start(): Boolean {
        if (started) return true
        val component = NowPlayingAccess.listenerComponent(appContext)
        return try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            started = true
            watch(sessionManager.getActiveSessions(component))
            true
        } catch (e: SecurityException) {
            // Access not granted (or revoked in the meantime): nothing to observe.
            stop()
            publish(null)
            false
        }
    }

    fun stop() {
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        unwatchAll()
        started = false
    }

    private fun watch(controllers: List<MediaController>) {
        unwatchAll()
        watched = controllers.map { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            controller.registerCallback(callback, mainHandler)
            controller to callback
        }
        refresh()
    }

    private fun unwatchAll() {
        watched.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        watched = emptyList()
    }

    private fun refresh() {
        val controller = NowPlayingLogic.pickPlaying(
            watched.map { it.first },
            stateOf = { it.playbackState?.state },
            titleOf = { titleOf(it.metadata) },
        )
        publish(controller?.let(::toTrack))
    }

    private fun titleOf(metadata: MediaMetadata?): String? = metadata?.let {
        NowPlayingLogic.firstNonBlank(
            it.getString(MediaMetadata.METADATA_KEY_TITLE),
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        )
    }

    private fun toTrack(controller: MediaController): NowPlayingTrack? {
        val metadata = controller.metadata ?: return null
        val title = titleOf(metadata) ?: return null
        return NowPlayingTrack(
            title = title,
            artist = NowPlayingLogic.firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR),
            ),
            album = NowPlayingLogic.firstNonBlank(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            packageName = controller.packageName,
            appLabel = appLabel(controller.packageName),
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        )
    }

    private fun publish(track: NowPlayingTrack?) {
        // Artwork often arrives in a second metadata update, so "has art" is part of the identity.
        val key = track?.let { listOf(it.packageName, it.title, it.artist, it.album, it.durationMs, it.artwork != null) }
        if (key == lastKey && lastKey != null) return
        lastKey = key
        onTrackChanged(track)
    }

    private fun appLabel(packageName: String): String? = try {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        // Not visible under Android 11+ package visibility (no launcher entry): the card just omits the app name.
        null
    }
}
