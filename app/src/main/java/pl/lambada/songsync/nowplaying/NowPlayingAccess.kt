package pl.lambada.songsync.nowplaying

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * DataStore keys for the optional "Now playing" card (stored in the app's shared "settings" DataStore).
 *  - [nowPlayingEnabledKey]: master switch (Settings > Now playing). Default on, but nothing is read until the user
 *    also grants notification access, so existing users get no new permission.
 *  - [nowPlayingPromptDismissedKey]: the user closed the one-time "See what's playing" card on Home; never shown again.
 */
val nowPlayingEnabledKey = booleanPreferencesKey("now_playing_enabled")
val nowPlayingPromptDismissedKey = booleanPreferencesKey("now_playing_prompt_dismissed")

object NowPlayingAccess {

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, NowPlayingListenerService::class.java)

    /** True when the user has enabled this app under "Notification access" (needed to see other apps' sessions). */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Opens the system page where notification access is granted. Android 11+ can jump straight to this app's
     * toggle; older versions (and OEMs without the detail page) get the full list. On API 21 the action exists but
     * its constant was still hidden, so the literal value is used.
     */
    fun openSettings(context: Context) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        listenerComponent(context).flattenToString()
                    )
                )
            }
            add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        startFirstAvailable(context, intents)
    }

    /**
     * App info page. On Android 13+ a sideloaded APK (e.g. from GitHub releases) gets "Restricted setting" when it
     * asks for notification access; the user unlocks it there via ⋮ > "Allow restricted settings".
     */
    fun openAppInfo(context: Context) {
        startFirstAvailable(
            context,
            listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                Intent(Settings.ACTION_SETTINGS),
            )
        )
    }

    private fun startFirstAvailable(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                // Started from a non-Activity context too (application context), so a new task may be required.
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // This device has no such settings page: fall through to the next, more generic one.
            }
        }
    }
}
