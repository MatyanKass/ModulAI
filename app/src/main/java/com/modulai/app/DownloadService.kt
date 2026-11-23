package com.modulai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows progress while [DownloadQueue] has work.
 * The transfer itself runs in the queue; this service only mirrors it and stops when it is idle.
 */
class DownloadService : Service() {
    /** What the notification shows; null when nothing is downloading or waiting. */
    private data class Status(val fileName: String, val percent: Int?, val waiting: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // Must happen on every start, even when the queue is already empty again:
        // the system kills the app if a service started as foreground never becomes one.
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(status(DownloadQueue.items.value)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: IllegalStateException) {
            // Not allowed right now (background start, or the daily dataSync budget is spent).
            // The download goes on for as long as the process lives.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) head(DownloadQueue.items.value)?.let(DownloadQueue::cancel)

        if (watcher == null) watcher = scope.launch {
            // Progress arrives many times a second; the notification only needs whole percents.
            DownloadQueue.items.map(::status).distinctUntilChanged().collect { status ->
                if (status == null) stop()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to six hours a day and requires stopping when told. */
    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        scope.cancel()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The download the notification is about: the running one, or the next in line. */
    private fun head(items: List<DownloadItem>): DownloadItem? =
        items.firstOrNull { it.state == DownloadState.RUNNING } ?: items.firstOrNull { it.state == DownloadState.WAITING }

    private fun status(items: List<DownloadItem>): Status? {
        val head = head(items) ?: return null
        val percent = head.progress?.takeIf { it.totalBytes > 0 }?.let { (it.fraction * 100).toInt() }
        return Status(head.fileName, percent, items.count { it.state == DownloadState.WAITING && it.id != head.id })
    }

    private fun notification(status: Status?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(status?.fileName ?: getString(R.string.notif_preparing))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (status != null) {
            val parts = listOfNotNull(
                status.percent?.let { getString(R.string.notif_percent, it) },
                if (status.waiting > 0) getString(R.string.notif_queue, status.waiting) else null,
            )
            if (parts.isNotEmpty()) builder.setContentText(parts.joinToString(" · "))
            builder.setProgress(100, status.percent ?: 0, status.percent == null)
            val cancel = PendingIntent.getService(
                this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.cancel),
                    cancel,
                ).build(),
            )
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.modulai.app.CANCEL_DOWNLOAD"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Started from the background: the download still runs, only without the service.
            }
        }
    }
}
