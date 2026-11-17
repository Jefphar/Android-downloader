package com.example.idmdl

import android.webkit.MimeTypeMap
import java.util.Locale

fun formatSize(b: Long): String {
    if (b < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatSpeed(b: Long): String = formatSize(b) + "/s"

fun formatEta(seconds: Long): String = when {
    seconds < 0 -> "--"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private val urlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

fun findUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value }
