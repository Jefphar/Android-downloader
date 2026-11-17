package com.fastdownloader

import android.net.Uri
import android.text.format.Formatter
import java.net.URLDecoder

fun fmtSize(bytes: Long): String = Formatter.formatFileSize(Downloads.app, bytes)

fun fmtSpeed(bps: Long): String = fmtSize(bps) + "/s"

fun fmtEta(seconds: Long): String {
    if (seconds < 0) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun sanitizeFileName(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120)

fun fileNameFromUrl(url: String): String {
    val seg = try {
        Uri.parse(url).lastPathSegment
    } catch (e: Exception) {
        null
    }
    val name = sanitizeFileName(seg ?: "")
    return if (name.isBlank()) "download" else name
}

fun fileNameFromDisposition(header: String?): String? {
    if (header == null) return null
    val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)
    if (star != null) {
        val decoded = try {
            URLDecoder.decode(star.groupValues[1].trim(), "UTF-8")
        } catch (e: Exception) {
            null
        }
        if (!decoded.isNullOrBlank()) return sanitizeFileName(decoded)
    }
    val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
    if (plain != null) {
        val n = sanitizeFileName(plain.groupValues[1].trim())
        if (n.isNotBlank()) return n
    }
    return null
}

val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
