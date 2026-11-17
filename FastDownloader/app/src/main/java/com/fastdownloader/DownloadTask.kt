package com.fastdownloader

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads one file the way IDM does: the file is cut into many byte ranges and
 * [MAX_THREADS] connections pull ranges from a shared queue in parallel. Fast
 * connections simply take more ranges, so nothing sits idle. Progress of every
 * range is saved, so pause/resume (and even a process kill) continue where they left off.
 */
class DownloadTask(private val ctx: Context, private val item: DownloadItem) : Runnable {

    companion object {
        const val MAX_THREADS = 8
        const val MAX_RETRIES = 6
        private const val UA = "Mozilla/5.0 (Linux; Android 14) FastDownloader/1.0"
    }

    @Volatile private var cancelled = false
    @Volatile private var failure: Exception? = null
    private val conns = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private var finalUrl: String = item.url
    private val partFile = File(ctx.getExternalFilesDir("partial") ?: ctx.filesDir, "${item.id}.part")

    private var lastTime = SystemClock.elapsedRealtime()
    private var lastBytes = 0L
    private var lastSave = lastTime

    fun cancel() {
        cancelled = true
        for (c in conns) {
            try {
                c.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    override fun run() {
        try {
            item.error = null
            probe()
            if (cancelled) return
            lastBytes = item.downloaded.get()
            if (item.supportsRanges && item.totalBytes > 0) runChunked() else runSingle()
            if (cancelled) return
            val f = failure
            if (f != null) throw f
            finalizeFile()
            item.status = Status.COMPLETED
        } catch (e: Exception) {
            if (!cancelled) {
                item.status = Status.FAILED
                item.error = e.message ?: e.javaClass.simpleName
            }
        } finally {
            item.speedBps = 0
            Downloads.save()
            Downloads.notifyChanged()
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(url: String, range: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    /** Opens [url], following redirects by hand (also http<->https). */
    private fun connect(url: String, range: String?): Pair<HttpURLConnection, String> {
        var current = url
        for (i in 0 until 10) {
            val c = open(current, range)
            val code = c.responseCode
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Redirect without Location")
                current = URL(URL(current), loc).toString()
            } else {
                return Pair(c, current)
            }
        }
        throw IOException("Too many redirects")
    }

    private fun probe() {
        val (c, url) = connect(item.url, "bytes=0-0")
        try {
            finalUrl = url
            val code = c.responseCode
            var total = -1L
            var ranges = false
            when (code) {
                206 -> {
                    val cr = c.getHeaderField("Content-Range")
                    total = cr?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    ranges = total > 0
                }
                200 -> {
                    total = c.getHeaderFieldLong("Content-Length", -1L)
                    ranges = false
                }
                else -> throw IOException("Server returned HTTP $code")
            }

            fileNameFromDisposition(c.getHeaderField("Content-Disposition"))?.let { item.fileName = it }
            val ct = c.contentType?.substringBefore(';')?.trim()
            if (!ct.isNullOrBlank() && ct != "application/octet-stream") item.mime = ct

            if (item.totalBytes != total || item.supportsRanges != ranges) {
                resetProgress()
            }
            item.totalBytes = total
            item.supportsRanges = ranges

            if (ranges && total > 0) {
                if (!partFile.exists() && item.chunks.any { it.done > 0 }) resetProgress()
                if (item.chunks.isEmpty()) {
                    val chunkSize = (total / 32).coerceIn(512L * 1024, 8L * 1024 * 1024)
                    val list = ArrayList<Chunk>()
                    var start = 0L
                    while (start < total) {
                        val end = minOf(start + chunkSize - 1, total - 1)
                        list.add(Chunk(start, end))
                        start = end + 1
                    }
                    item.chunks = list
                    item.downloaded.set(0)
                }
                val dir = partFile.parentFile
                val needed = total - item.downloaded.get()
                if (dir != null && dir.usableSpace < needed) throw IOException("Not enough storage space")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun resetProgress() {
        item.chunks = emptyList()
        item.downloaded.set(0)
        partFile.delete()
    }

    // ------------------------------------------------------------ chunked

    private fun runChunked() {
        val total = item.totalBytes
        RandomAccessFile(partFile, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }
        val queue = ConcurrentLinkedQueue<Chunk>(item.chunks.filter { it.remaining > 0 })
        val threads = minOf(MAX_THREADS, maxOf(1, queue.size))
        val pool = Executors.newFixedThreadPool(threads)
        for (i in 0 until threads) {
            pool.execute { worker(queue) }
        }
        pool.shutdown()
        while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            tick()
            if (cancelled || failure != null) {
                // make blocked reads fail quickly
                for (c in conns) {
                    try {
                        c.disconnect()
                    } catch (e: Exception) {
                    }
                }
            }
        }
        if (!cancelled && failure == null && item.chunks.any { it.remaining > 0 }) {
            failure = IOException("Download incomplete")
        }
    }

    private fun worker(queue: ConcurrentLinkedQueue<Chunk>) {
        val buf = ByteArray(128 * 1024)
        while (!cancelled && failure == null) {
            val chunk = queue.poll() ?: return
            if (chunk.remaining <= 0) continue
            try {
                downloadChunk(chunk, buf)
            } catch (e: Exception) {
                if (cancelled) return
                chunk.retries++
                if (chunk.retries > MAX_RETRIES) {
                    failure = e
                    return
                }
                try {
                    Thread.sleep(minOf(1000L * chunk.retries, 5000L))
                } catch (ie: InterruptedException) {
                    return
                }
                queue.add(chunk)
            }
        }
    }

    private fun downloadChunk(chunk: Chunk, buf: ByteArray) {
        val from = chunk.start + chunk.done
        val (c, _) = connect(finalUrl, "bytes=$from-${chunk.end}")
        conns.add(c)
        try {
            if (c.responseCode != 206) throw IOException("Server returned HTTP ${c.responseCode}")
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(from)
                c.inputStream.use { input ->
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val take = minOf(n.toLong(), chunk.remaining).toInt()
                        if (take <= 0) break
                        raf.write(buf, 0, take)
                        chunk.done = chunk.done + take
                        item.downloaded.addAndGet(take.toLong())
                        if (chunk.remaining <= 0) break
                    }
                }
            }
            if (!cancelled && chunk.remaining > 0) throw IOException("Connection closed early")
        } finally {
            conns.remove(c)
            c.disconnect()
        }
    }

    // ------------------------------------------------------------- single

    /** Fallback for servers without Range support or unknown size: one stream, no resume. */
    private fun runSingle() {
        item.chunks = emptyList()
        item.downloaded.set(0)
        lastBytes = 0
        val (c, _) = connect(finalUrl, null)
        conns.add(c)
        try {
            if (c.responseCode != 200) throw IOException("Server returned HTTP ${c.responseCode}")
            val total = c.getHeaderFieldLong("Content-Length", -1L)
            item.totalBytes = total
            FileOutputStream(partFile, false).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        item.downloaded.addAndGet(n.toLong())
                        tick()
                    }
                }
            }
            if (!cancelled && total > 0 && item.downloaded.get() < total) {
                throw IOException("Connection closed early")
            }
        } finally {
            conns.remove(c)
            c.disconnect()
        }
    }

    // ------------------------------------------------------------- common

    /** Called from the task thread: updates speed, refreshes the UI, saves progress now and then. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTime
        if (dt < 500) return
        val bytes = item.downloaded.get()
        val inst = (bytes - lastBytes) * 1000 / dt
        item.speedBps = if (item.speedBps == 0L) inst else (item.speedBps * 6 + inst * 4) / 10
        lastBytes = bytes
        lastTime = now
        Downloads.notifyChanged()
        if (now - lastSave > 2000) {
            Downloads.save()
            lastSave = now
        }
    }

    /** Moves the finished .part file into the public Downloads/FastDownloader folder. */
    private fun finalizeFile() {
        if (item.supportsRanges && item.totalBytes > 0 && partFile.length() != item.totalBytes) {
            throw IOException("File size mismatch")
        }
        val name = item.fileName
        val ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_")).ifBlank {
            name.substringAfterLast('.', "")
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: item.mime
            ?: "application/octet-stream"
        item.mime = mime

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open output file")
            out.use { o ->
                FileInputStream(partFile).use { it.copyTo(o, 256 * 1024) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            throw e
        }
        item.savedUri = uri.toString()
        partFile.delete()
    }
}
