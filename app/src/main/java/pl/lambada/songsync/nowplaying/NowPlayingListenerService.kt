package pl.lambada.songsync.nowplaying

import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant "Notification access" to the app. Android only hands out other apps' media
 * sessions ([android.media.session.MediaSessionManager.getActiveSessions]) to an enabled notification listener
 * (or to apps holding the system-only MEDIA_CONTENT_CONTROL permission), and that check is done against this
 * component's name. The service never reads, stores or reacts to notifications itself.
 */
class NowPlayingListenerService : NotificationListenerService()
