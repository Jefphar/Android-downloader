package com.fastdownloader

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** One byte range of the file. [done] is how many bytes of it are already written. */
class Chunk(val start: Long, val end: Long, @Volatile var done: Long = 0L) {
    @Volatile
    var retries = 0
    val length: Long get() = end - start + 1
    val remaining: Long get() = length - done
}

class DownloadItem(val id: Long, val url: String, @Volatile var fileName: String) {
    @Volatile var status: Status = Status.QUEUED
    @Volatile var totalBytes: Long = -1L
    @Volatile var supportsRanges: Boolean = false
    @Volatile var error: String? = null
    @Volatile var savedUri: String? = null
    @Volatile var mime: String? = null
    @Volatile var speedBps: Long = 0L
    @Volatile var chunks: List<Chunk> = emptyList()
    var createdAt: Long = System.currentTimeMillis()
    val downloaded = AtomicLong(0L)

    /** 0..100, or -1 when the size is unknown. */
    val progress: Int
        get() = if (totalBytes > 0) ((downloaded.get() * 100) / totalBytes).toInt().coerceIn(0, 100) else -1

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("url", url)
        o.put("fileName", fileName)
        o.put("total", totalBytes)
        o.put("status", status.name)
        o.put("ranges", supportsRanges)
        o.put("error", error ?: JSONObject.NULL)
        o.put("uri", savedUri ?: JSONObject.NULL)
        o.put("mime", mime ?: JSONObject.NULL)
        o.put("created", createdAt)
        o.put("downloaded", downloaded.get())
        val arr = JSONArray()
        for (c in chunks) {
            arr.put(JSONArray().put(c.start).put(c.end).put(c.done))
        }
        o.put("chunks", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val item = DownloadItem(o.getLong("id"), o.getString("url"), o.getString("fileName"))
            item.totalBytes = o.optLong("total", -1L)
            item.status = try {
                Status.valueOf(o.getString("status"))
            } catch (e: Exception) {
                Status.PAUSED
            }
            item.supportsRanges = o.optBoolean("ranges", false)
            item.error = if (o.isNull("error")) null else o.optString("error")
            item.savedUri = if (o.isNull("uri")) null else o.optString("uri")
            item.mime = if (o.isNull("mime")) null else o.optString("mime")
            item.createdAt = o.optLong("created", item.id)
            val arr = o.optJSONArray("chunks")
            val list = ArrayList<Chunk>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list.add(Chunk(a.getLong(0), a.getLong(1), a.getLong(2)))
                }
            }
            item.chunks = list
            item.downloaded.set(
                if (list.isNotEmpty()) list.sumOf { it.done } else o.optLong("downloaded", 0L)
            )
            return item
        }
    }
}
