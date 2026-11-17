package com.fastdownloader

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Keeps the process alive (and shows progress) while downloads are running. */
class DownloadService : Service() {

    private val listener: () -> Unit = { update() }
    private var wake: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
        Notifier.createChannels(this)
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForeground(1, build(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wake == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastDownloader:downloads")
        }
        if (wake?.isHeld == false) wake?.acquire(6L * 60 * 60 * 1000)
        update()
        return START_NOT_STICKY
    }

    private fun update() {
        val active = Downloads.items.filter { it.status == Status.DOWNLOADING }
        if (active.isEmpty()) {
            if (wake?.isHeld == true) wake?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            return
        }
        getSystemService(android.app.NotificationManager::class.java).notify(1, build(active))
    }

    private fun build(active: List<DownloadItem>): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val done = active.sumOf { it.downloaded.get() }
        val speed = active.sumOf { it.speedBps }
        val title = when (active.size) {
            0 -> "FastDownloader"
            1 -> active[0].fileName
            else -> "${active.size} downloads"
        }
        val b = NotificationCompat.Builder(this, Notifier.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (active.isNotEmpty()) {
            if (total > 0) {
                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                b.setProgress(100, pct, false)
                b.setContentText("$pct%  •  ${fmtSpeed(speed)}")
            } else {
                b.setProgress(0, 0, true)
                b.setContentText(fmtSpeed(speed))
            }
        }
        return b.build()
    }

    override fun onDestroy() {
        Downloads.removeListener(listener)
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
