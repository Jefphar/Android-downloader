package com.example.idmdl

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DownloadManager.init(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) incoming = urlFrom(intent)
        setContent {
            AppTheme {
                DownloaderApp(incoming = incoming, onConsumed = { incoming = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        urlFrom(intent)?.let { incoming = it }
    }

    private fun urlFrom(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_SEND -> findUrl(i.getStringExtra(Intent.EXTRA_TEXT))
        Intent.ACTION_VIEW -> i.data?.toString()
        else -> null
    }
}

fun openFile(ctx: android.content.Context, item: DownloadItem) {
    val uri = item.savedUri ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mimeOf(item.fileName))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}
