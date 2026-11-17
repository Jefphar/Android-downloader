package com.example.idmdl

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderApp(incoming: String?, onConsumed: () -> Unit) {
    val items by DownloadManager.items.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            prefill = incoming
            showAdd = true
            onConsumed()
        }
    }

    val totalSpeed = items.filter { it.status == Status.DOWNLOADING }.sumOf { it.speed }
    val shown = when (tab) {
        1 -> items.filter { it.status != Status.COMPLETED }
        2 -> items.filter { it.status == Status.COMPLETED }
        else -> items
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Fast Downloader")
                            Text(
                                if (totalSpeed > 0) "↓ ${formatSpeed(totalSpeed)}" else "Idle",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { DownloadManager.pauseAll() }) {
                            Icon(Icons.Default.PauseCircle, contentDescription = "Pause all")
                        }
                    }
                )
                TabRow(selectedTabIndex = tab) {
                    listOf("All", "Active", "Done").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { prefill = ""; showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add URL") }
            )
        }
    ) { pad ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "No downloads yet.\nTap Add URL, or share a link to this app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { item ->
                    DownloadCard(item, onDelete = {
                        if (item.status == Status.COMPLETED) confirmDelete = item
                        else DownloadManager.remove(item.id, false)
                    })
                }
            }
        }
    }

    if (showAdd) AddDialog(prefill, onDismiss = { showAdd = false }) { url, name, conns ->
        DownloadManager.add(url, name, conns)
        showAdd = false
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove download") },
            text = { Text(item.displayName) },
            confirmButton = {
                TextButton(onClick = { DownloadManager.remove(item.id, true); confirmDelete = null }) {
                    Text("Delete file too")
                }
            },
            dismissButton = {
                TextButton(onClick = { DownloadManager.remove(item.id, false); confirmDelete = null }) {
                    Text("Remove from list")
                }
            }
        )
    }
}

@Composable
fun AddDialog(prefill: String, onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(prefill) }
    var name by remember { mutableStateOf("") }
    var conns by remember { mutableFloatStateOf(8f) }

    LaunchedEffect(Unit) {
        if (url.isBlank()) findUrl(clipboard.getText()?.text)?.let { url = it }
    }
    val valid = url.trim().startsWith("http://", true) || url.trim().startsWith("https://", true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL") }, singleLine = false, maxLines = 3,
                    isError = url.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("File name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Connections: ${conns.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = conns, onValueChange = { conns = it }, valueRange = 1f..16f, steps = 14)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(url, name, conns.toInt()) }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DownloadCard(item: DownloadItem, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                when (item.status) {
                    Status.COMPLETED -> IconButton(onClick = { openFile(ctx, item) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open")
                    }
                    Status.DOWNLOADING, Status.QUEUED -> IconButton(onClick = { DownloadManager.pause(item.id) }) {
                        Icon(Icons.Default.Pause, "Pause")
                    }
                    else -> IconButton(onClick = { DownloadManager.resume(item.id) }) {
                        Icon(Icons.Default.PlayArrow, "Resume")
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
            }

            if (item.status != Status.COMPLETED) {
                if (item.total > 0 && item.segments.isNotEmpty()) SegmentBar(item)
                else if (item.status == Status.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun statusLine(i: DownloadItem): String = when (i.status) {
    Status.QUEUED -> "Queued"
    Status.DOWNLOADING ->
        if (i.total > 0)
            "${(i.fraction * 100).toInt()}% • ${formatSize(i.downloaded)} / ${formatSize(i.total)} • " +
                "${formatSpeed(i.speed)} • ETA ${formatEta(i.etaSeconds)} • ${i.segments.size}x"
        else "${formatSize(i.downloaded)} • ${formatSpeed(i.speed)}"
    Status.PAUSED -> "Paused • ${formatSize(i.downloaded)} / ${formatSize(i.total)}"
    Status.FAILED -> "Failed: ${i.error ?: "unknown error"}"
    Status.COMPLETED -> "${formatSize(i.total)} • Saved to Downloads/FastDownloader"
}

/** IDM-style bar: one block per connection, each filling independently. */
@Composable
fun SegmentBar(item: DownloadItem) {
    Row(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        item.segments.forEach { s ->
            val len = s.length.coerceAtLeast(1L)
            val frac = if (s.length > 0) (s.downloaded.toFloat() / s.length).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.weight(len.toFloat()).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
