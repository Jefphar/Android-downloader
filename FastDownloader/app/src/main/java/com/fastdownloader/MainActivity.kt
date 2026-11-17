package com.fastdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadAdapter
    private lateinit var empty: TextView
    private var lastClip: String? = null

    private val listener: () -> Unit = { refresh() }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        empty = findViewById(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        adapter = DownloadAdapter(
            onOpen = { Downloads.open(this, it) },
            onRemove = { confirmRemove(it) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { showAddDialog(null) }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = URL_REGEX.find(text)?.value
            showAddDialog(url ?: text)
        }
    }

    override fun onStart() {
        super.onStart()
        Downloads.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Downloads.removeListener(listener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun refresh() {
        adapter.refresh()
        empty.visibility = if (Downloads.items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** IDM-style: offer to download a link that was just copied. */
    private fun checkClipboard() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = try {
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (e: Exception) {
            null
        } ?: return
        if (text == lastClip) return
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(text)) return
        lastClip = text
        if (Downloads.items.any { it.url == text }) return
        Snackbar.make(findViewById(R.id.fab), "Download copied link?", Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.fab)
            .setAction("Download") { Downloads.add(text) }
            .show()
    }

    private fun showAddDialog(prefill: String?) {
        val input = EditText(this).apply {
            hint = "https://example.com/file.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            if (prefill != null) setText(prefill)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New download")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url)) {
                    Downloads.add(url)
                } else {
                    Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(item: DownloadItem) {
        val options = if (item.status == Status.COMPLETED && item.savedUri != null) {
            arrayOf("Remove from list", "Remove and delete file")
        } else {
            arrayOf("Remove from list")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.fileName)
            .setItems(options) { _, which -> Downloads.remove(item.id, which == 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
