package com.example.idmdl

import org.json.JSONArray
import org.json.JSONObject

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class Segment(val index: Int, val start: Long, val end: Long, val downloaded: Long) {
    /** Length in bytes, or -1 when the server did not tell us the file size. */
    val length: Long get() = if (end >= 0) end - start + 1 else -1L
    val done: Boolean get() = length >= 0 && downloaded >= length
}

data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val connections: Int,
    val total: Long = -1,
    val resumable: Boolean = false,
    val status: Status = Status.QUEUED,
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    val savedUri: String? = null,
    val speed: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val downloaded: Long get() = segments.sumOf { it.downloaded }
    val fraction: Float
        get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val etaSeconds: Long get() = if (speed > 0 && total > 0) (total - downloaded) / speed else -1
    val displayName: String get() = fileName.ifBlank { url }
}

fun DownloadItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("url", url); put("fileName", fileName); put("connections", connections)
    put("total", total); put("resumable", resumable); put("status", status.name)
    put("error", error ?: JSONObject.NULL); put("savedUri", savedUri ?: JSONObject.NULL)
    put("createdAt", createdAt)
    put("segments", JSONArray().also { arr ->
        segments.forEach {
            arr.put(JSONObject().put("i", it.index).put("s", it.start).put("e", it.end).put("d", it.downloaded))
        }
    })
}

fun itemFromJson(o: JSONObject): DownloadItem {
    val segs = o.getJSONArray("segments")
    return DownloadItem(
        id = o.getString("id"),
        url = o.getString("url"),
        fileName = o.getString("fileName"),
        connections = o.getInt("connections"),
        total = o.getLong("total"),
        resumable = o.getBoolean("resumable"),
        status = Status.valueOf(o.getString("status")),
        segments = (0 until segs.length()).map {
            val s = segs.getJSONObject(it)
            Segment(s.getInt("i"), s.getLong("s"), s.getLong("e"), s.getLong("d"))
        },
        error = if (o.isNull("error")) null else o.getString("error"),
        savedUri = if (o.isNull("savedUri")) null else o.getString("savedUri"),
        createdAt = o.getLong("createdAt")
    )
}
