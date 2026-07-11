package pl.lambada.songsync.util.batch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * progress into an ongoing notification (with a rough time-left estimate once the pace is known). Tapping
 * the notification reopens the app (single task, no restart) directly on the batch screen.
 *
 * When the run finishes the ongoing notification is replaced by a one-shot RESULTS notification on its own
 * (default-importance) channel: how many synced, plain, without lyrics, errored and skipped, plus how many
 * plain lyrics are waiting behind "Add all". Tapping it opens the batch screen. A user-cancelled run posts
 * nothing: the user was there for it. The service stops itself either way.
 */
class BatchDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createResultsChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = BatchDownloadController.progressFlow.value
        startInForeground(buildProgressNotification(initial))

        serviceScope.launch {
            BatchDownloadController.progressFlow.collectLatest { progress ->
                if (progress.finished) {
                    postCompletionNotification()
                    stopSelf()
                } else {
                    runCatching {
                        NotificationManagerCompat.from(this@BatchDownloadService)
                            .notify(NOTIFICATION_ID, buildProgressNotification(progress))
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

    private fun openBatchIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_BATCH
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(progress: BatchDownloadController.Progress): Notification {
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
            .setSubText(estimateTimeLeft(progress))
            .setContentIntent(openBatchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(progress.total.coerceAtLeast(1), progress.done, progress.total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /**
     * Rough "about N min left" from the run's average pace so far. Only shown once a few songs are done,
     * so one slow first lookup doesn't produce a silly estimate.
     */
    private fun estimateTimeLeft(progress: BatchDownloadController.Progress): String? {
        val startedAt = BatchDownloadController.runStartedAt
        if (startedAt <= 0L || progress.done < 3 || progress.total <= progress.done) return null
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < 10_000) return null
        val leftMs = elapsed / progress.done * (progress.total - progress.done)
        return getString(R.string.batch_notification_eta, formatDuration(leftMs))
    }

    private fun postCompletionNotification() {
        val c = BatchDownloadController
        // The user pressed Stop themselves; they were looking at the screen. Nothing to announce.
        if (c.status == BatchDownloadController.Status.CANCELLED) return
        // Nothing ran at all (e.g. everything filtered out before the first song).
        if (c.processedCount == 0 && c.status != BatchDownloadController.Status.DONE) return

        val title = if (c.status == BatchDownloadController.Status.RATE_LIMITED)
            getString(R.string.batch_rate_limited_notif_title)
        else
            getString(R.string.batch_finished_notif_title)

        val lines = buildList {
            add(getString(R.string.notif_line_synced, c.syncedCount))
            add(getString(R.string.notif_line_unsynced, c.unsyncedCount))
            add(getString(R.string.notif_line_no_lyrics, c.noLyricsCount))
            if (c.failedCount > 0) add(getString(R.string.notif_line_failed, c.failedCount))
            if (c.skippedCount > 0) add(getString(R.string.notif_line_skipped, c.skippedCount))
            if (c.pendingUnsynced.isNotEmpty())
                add(getString(R.string.notif_line_pending_unsynced, c.pendingUnsynced.size))
            if (c.runStartedAt > 0 && c.runFinishedAt > c.runStartedAt)
                add(getString(R.string.batch_finished_in, formatDuration(c.runFinishedAt - c.runStartedAt)))
        }

        val summary = getString(
            R.string.batch_summary_short,
            c.syncedCount, c.unsyncedCount, c.noLyricsCount
        )

        val notification = NotificationCompat.Builder(this, RESULTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_song)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(lines.joinToString("\n"))
                    .setSummaryText(getString(R.string.notif_tap_for_details))
            )
            .setContentIntent(openBatchIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(RESULTS_NOTIFICATION_ID, notification)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(1)
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) getString(R.string.duration_min_sec, min.toInt(), sec.toInt())
        else getString(R.string.duration_sec, sec.toInt())
    }

    private fun createResultsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RESULTS_CHANNEL_ID,
            getString(R.string.batch_results_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.batch_results_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Only the ongoing progress notification; the results notification must outlive the service.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 3
        const val RESULTS_NOTIFICATION_ID = 4
        const val RESULTS_CHANNEL_ID = "batch_results"
        const val ACTION_OPEN_BATCH = "pl.lambada.songsync.OPEN_BATCH"
    }
}
