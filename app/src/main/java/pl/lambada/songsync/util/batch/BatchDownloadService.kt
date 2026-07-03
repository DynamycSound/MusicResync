package pl.lambada.songsync.util.batch

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.lambada.songsync.MainActivity
import pl.lambada.songsync.R

/**
 * Foreground service that keeps the process alive while [BatchDownloadController] works, and mirrors its
 * progress into an ongoing notification. Tapping the notification reopens the app (single task, no restart)
 * directly on the batch screen. The service stops itself as soon as the run finishes or is cancelled.
 */
class BatchDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = BatchDownloadController.progressFlow.value
        startInForeground(buildNotification(initial))

        serviceScope.launch {
            BatchDownloadController.progressFlow.collectLatest { progress ->
                if (progress.finished) {
                    stopSelf()
                } else {
                    runCatching {
                        NotificationManagerCompat.from(this@BatchDownloadService)
                            .notify(NOTIFICATION_ID, buildNotification(progress))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(progress: BatchDownloadController.Progress): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_BATCH
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.batch_download_lyrics)
        val text = if (progress.total > 0) {
            getString(
                R.string.batch_notification_progress,
                progress.done, progress.total,
                progress.currentTitle ?: getString(R.string.unknown)
            )
        } else getString(R.string.please_wait)

        return NotificationCompat.Builder(this, getString(R.string.batch_download_lyrics))
            .setSmallIcon(R.drawable.ic_song)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(progress.total.coerceAtLeast(1), progress.done, progress.total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 3
        const val ACTION_OPEN_BATCH = "pl.lambada.songsync.OPEN_BATCH"
    }
}
