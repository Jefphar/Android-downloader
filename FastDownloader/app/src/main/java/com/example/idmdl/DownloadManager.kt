package com.example.idmdl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * IDM-style engine: the file is split into N byte ranges, each fetched over its own
 * connection and written at the right offset of a shared .part file. Progress is kept
 * per segment, so pause/resume (and app restarts) continue exactly where they stopped.
 */
object DownloadManager {
    private const val MAX_PARALLEL_FILES = 3
    private const val MIN_SEGMENT_BYTES = 256L * 1024
    const val CHANNEL_ID = "downloads"
    private const val DONE_CHANNEL_ID = "done"

    private lateinit var ctx: Context
    @Volatile private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val queueGate = Semaphore(MAX_PARALLEL_FILES)

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val stateFile get() = File(ctx.filesDir, "downloads.json")
    private val partDir: File
        get() = (ctx.getExternalFilesDir("parts") ?: File(ctx.filesDir, "parts")).also { it.mkdirs() }

    // ---------------------------------------------------------------- setup

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        ctx = context.applicationContext
        inited = true
        createChannels()
        load()
        startTicker()
    }

    private fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL_ID, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun load() {
        val f = stateFile
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val list = (0 until arr.length()).map { itemFromJson(arr.getJSONObject(it)) }.map {
                // Anything that was running when the app died is simply paused.
                if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED)
                    it.copy(status = Status.PAUSED) else it
            }
            _items.value = list
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            _items.value.forEach { arr.put(it.toJson()) }
            val tmp = File(ctx.filesDir, "downloads.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(stateFile)
        } catch (_: Exception) {
        }
    }

    /** Once a second: compute smoothed speed per item and persist progress. */
    private fun startTicker() {
        scope.launch {
            var last = emptyMap<String, Long>()
            while (true) {
                delay(1000)
                val prev = last
                last = _items.value.associate { it.id to it.downloaded }
                var active = false
                _items.update { list ->
                    list.map { i ->
                        if (i.status == Status.DOWNLOADING) {
                            active = true
                            val d = (i.downloaded - (prev[i.id] ?: i.downloaded)).coerceAtLeast(0)
                            i.copy(speed = (i.speed * 0.4 + d * 0.6).toLong())
                        } else if (i.speed != 0L) i.copy(speed = 0) else i
                    }
                }
                if (active) save()
            }
        }
    }

    // ------------------------------------------------------------ public API

    fun add(url: String, fileName: String, connections: Int): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url.trim(),
            fileName = sanitize(fileName),
            connections = connections.coerceIn(1, 16)
        )
        _items.update { listOf(item) + it }
        save()
        launch(item.id)
        return item
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) {
            if (it.status == Status.QUEUED || it.status == Status.DOWNLOADING)
                it.copy(status = Status.PAUSED, speed = 0) else it
        }
        save()
    }

    fun resume(id: String) {
        val item = get(id) ?: return
        if (item.status != Status.PAUSED && item.status != Status.FAILED) return
        update(id) { it.copy(status = Status.QUEUED, error = null) }
        launch(id)
    }

    fun pauseAll() = _items.value.forEach { pause(it.id) }

    fun remove(id: String, deleteFile: Boolean) {
        val item = get(id)
        val job = jobs.remove(id)
        _items.update { l -> l.filterNot { it.id == id } }
        save()
        scope.launch {
            job?.cancelAndJoin()
            File(partDir, "$id.part").delete()
            if (deleteFile && item?.savedUri != null) {
                try {
                    ctx.contentResolver.delete(Uri.parse(item.savedUri), null, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- engine

    private fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun update(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
    }

    private fun bump(id: String, idx: Int, n: Long) {
        update(id) {
            it.copy(segments = it.segments.mapIndexed { i, s ->
                if (i == idx) s.copy(downloaded = s.downloaded + n) else s
            })
        }
    }

    private fun launch(id: String) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            queueGate.withPermit {
                try {
                    execute(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update(id) {
                        it.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName, speed = 0)
                    }
                    save()
                }
            }
        }
    }

    private suspend fun execute(id: String) {
        update(id) { it.copy(status = Status.DOWNLOADING, error = null) }
        if (get(id)?.segments.isNullOrEmpty()) probe(id)

        var item = get(id) ?: return
        val part = File(partDir, "$id.part")

        val needReset = item.downloaded > 0 && (!item.resumable || !part.exists())
        if (needReset) {
            update(id) { it.copy(segments = it.segments.map { s -> s.copy(downloaded = 0) }) }
            part.delete()
            item = get(id) ?: return
        }
        if (!part.exists()) part.createNewFile()
        if (item.total > 0 && part.length() != item.total) {
            RandomAccessFile(part, "rw").use { it.setLength(item.total) }
        }

        coroutineScope {
            item.segments.indices.map { i -> async { downloadSegment(id, i, part) } }.awaitAll()
        }
        finish(id, part)
    }

    /** Ask the server for size, range support and file name; then split into segments. */
    private fun probe(id: String) {
        val item = get(id) ?: return
        val req = Request.Builder().url(item.url).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            var total = -1L
            var ranged = false
            if (r.code == 206) {
                total = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
                ranged = total > 0
            } else {
                total = r.body.contentLength()
            }
            val name = item.fileName.ifBlank { nameFrom(r) }
            val n = if (!ranged || total < MIN_SEGMENT_BYTES * 2) 1
            else minOf(item.connections.toLong(), total / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)

            val segs = when {
                total <= 0 -> listOf(Segment(0, 0, -1, 0))
                n == 1 -> listOf(Segment(0, 0, total - 1, 0))
                else -> {
                    val chunk = total / n
                    (0 until n).map { i ->
                        val s = i * chunk
                        val e = if (i == n - 1) total - 1 else s + chunk - 1
                        Segment(i, s, e, 0)
                    }
                }
            }
            update(id) { it.copy(fileName = name, total = total, resumable = ranged, segments = segs) }
            save()
        }
    }

    private fun nameFrom(r: Response): String {
        val cd = r.header("Content-Disposition")
        if (cd != null) {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.let {
                    val n = try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                    if (n.isNotBlank()) return sanitize(n)
                }
        }
        val last = r.request.url.pathSegments.lastOrNull { it.isNotBlank() }
        val n = last?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
        return sanitize(n ?: "download")
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)

    private suspend fun downloadSegment(id: String, idx: Int, file: File) {
        var attempt = 0
        while (true) {
            val item = get(id) ?: return
            val seg = item.segments[idx]
            if (seg.done) return
            val before = seg.downloaded
            try {
                val pos = seg.start + seg.downloaded
                val rb = Request.Builder().url(item.url)
                if (item.resumable) rb.header("Range", "bytes=$pos-${seg.end}")
                val call = client.newCall(rb.build())
                // Make blocking socket reads abort the moment the coroutine is cancelled.
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { r ->
                        if (item.resumable && r.code != 206) throw IOException("Server ignored range (HTTP ${r.code})")
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        RandomAccessFile(file, "rw").use { raf ->
                            raf.seek(pos)
                            val input = r.body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                bump(id, idx, n.toLong())
                            }
                        }
                    }
                } finally {
                    handle.dispose()
                }
                if (seg.end < 0) return // unknown length: stream end means finished
                if (get(id)?.segments?.get(idx)?.done == true) return
                if ((get(id)?.segments?.get(idx)?.downloaded ?: before) == before && ++attempt > 4)
                    throw IOException("Server returned no data")
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                val after = get(id)?.segments?.getOrNull(idx)?.downloaded ?: before
                if (after > before) attempt = 0
                if (++attempt > 4) throw e
                delay(1500L * attempt)
            }
        }
    }

    /** Move the finished .part file into the public Downloads/FastDownloader folder. */
    private fun finish(id: String, part: File) {
        val item = get(id) ?: return
        if (item.total > 0 && item.downloaded < item.total) throw IOException("Incomplete download")
        val total = if (item.total > 0) item.total else item.downloaded
        val name = item.fileName.ifBlank { "download" }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastDownloader")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create file in Downloads")
        resolver.openOutputStream(uri)!!.use { out ->
            part.inputStream().use { it.copyTo(out, 256 * 1024) }
        }
        part.delete()
        update(id) {
            it.copy(status = Status.COMPLETED, total = total, savedUri = uri.toString(), speed = 0, error = null)
        }
        save()
        notifyDone(name, id)
    }

    private fun notifyDone(name: String, id: String) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, DONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText("Download complete")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
        } catch (_: Exception) {
        }
    }
}
