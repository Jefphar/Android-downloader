package com.example.idmdl

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Keeps the process alive and shows a notification while any download is active. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadManager.init(this)
        startForeground(
            NOTIF_ID, build("Starting downloads…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (watcher == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watcher = scope.launch {
                DownloadManager.items
                    .map { list ->
                        val active = list.filter { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }
                        active.size to active.sumOf { it.speed }
                    }
                    .distinctUntilChanged()
                    .collect { (count, speed) ->
                        if (count == 0) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            nm.notify(NOTIF_ID, build("$count active • ${formatSpeed(speed)}"))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fast Downloader")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
