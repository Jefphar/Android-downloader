package com.fastdownloader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Single source of truth for all downloads. List changes happen on the main thread. */
object Downloads {
    private const val MAX_ACTIVE = 3

    lateinit var app: Context
        private set

    val items = CopyOnWriteArrayList<DownloadItem>()

    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val saveLock = Any()
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        load()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        main.post {
            for (l in listeners) l()
        }
    }

    // ------------------------------------------------------------ actions

    fun add(url: String): DownloadItem {
        val item = DownloadItem(System.currentTimeMillis(), url, fileNameFromUrl(url))
        items.add(0, item)
        save()
        schedule()
        notifyChanged()
        return item
    }

    fun pause(id: Long) {
        val item = find(id) ?: return
        if (item.status == Status.DOWNLOADING || item.status == Status.QUEUED) {
            item.status = Status.PAUSED
            item.speedBps = 0
            tasks[id]?.cancel()
            save()
            notifyChanged()
            schedule()
        }
    }

    fun resume(id: Long) {
        val item = find(id) ?: return
        if (item.status == Status.PAUSED || item.status == Status.FAILED) {
            item.status = Status.QUEUED
            item.error = null
            save()
            notifyChanged()
            schedule()
        }
    }

    fun remove(id: Long, deleteFile: Boolean) {
        val item = find(id) ?: return
        tasks[id]?.cancel()
        items.remove(item)
        File(app.getExternalFilesDir("partial") ?: app.filesDir, "${item.id}.part").delete()
        if (deleteFile) {
            item.savedUri?.let {
                try {
                    app.contentResolver.delete(Uri.parse(it), null, null)
                } catch (e: Exception) {
                }
            }
        }
        save()
        notifyChanged()
        schedule()
    }

    fun open(ctx: Context, item: DownloadItem) {
        val uri = item.savedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), item.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "The file is no longer available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun find(id: Long): DownloadItem? = items.firstOrNull { it.id == id }

    // ---------------------------------------------------------- scheduling

    @Synchronized
    fun schedule() {
        var active = items.count { it.status == Status.DOWNLOADING }
        // oldest first
        for (item in items.reversed()) {
            if (active >= MAX_ACTIVE) break
            if (item.status == Status.QUEUED && !tasks.containsKey(item.id)) {
                start(item)
                active++
            }
        }
        if (active > 0) startService()
    }

    private fun start(item: DownloadItem) {
        item.status = Status.DOWNLOADING
        val task = DownloadTask(app, item)
        tasks[item.id] = task
        pool.execute {
            try {
                task.run()
            } finally {
                tasks.remove(item.id)
                if (item.status == Status.COMPLETED) Notifier.complete(app, item)
                schedule()
            }
        }
        notifyChanged()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            // Not allowed to start a foreground service from the background right now;
            // downloads still run while the process is alive.
        }
    }

    // --------------------------------------------------------- persistence

    private fun file() = File(app.filesDir, "downloads.json")

    fun save() {
        synchronized(saveLock) {
            try {
                val arr = JSONArray()
                for (i in items) arr.put(i.toJson())
                val tmp = File(app.filesDir, "downloads.json.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(file())
            } catch (e: Exception) {
            }
        }
    }

    private fun load() {
        try {
            val f = file()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<DownloadItem>()
            for (i in 0 until arr.length()) {
                val item = DownloadItem.fromJson(arr.getJSONObject(i))
                if (item.status == Status.DOWNLOADING || item.status == Status.QUEUED) {
                    item.status = Status.PAUSED
                }
                list.add(item)
            }
            list.sortByDescending { it.createdAt }
            items.addAll(list)
        } catch (e: Exception) {
        }
    }
}
