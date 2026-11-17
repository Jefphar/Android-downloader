package com.fastdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class DownloadAdapter(
    private val onOpen: (DownloadItem) -> Unit,
    private val onRemove: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private var lastCount = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val info: TextView = v.findViewById(R.id.info)
        val action: MaterialButton = v.findViewById(R.id.action)
        val remove: MaterialButton = v.findViewById(R.id.remove)
    }

    fun refresh() {
        val n = Downloads.items.size
        if (n != lastCount) {
            lastCount = n
            notifyDataSetChanged()
        } else if (n > 0) {
            notifyItemRangeChanged(0, n, "progress")
        }
    }

    override fun getItemCount() = Downloads.items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = Downloads.items.getOrNull(position) ?: return
        h.name.text = item.fileName

        val done = item.downloaded.get()
        val total = item.totalBytes
        val sizeText = if (total > 0) "${fmtSize(done)} of ${fmtSize(total)}" else fmtSize(done)

        when (item.status) {
            Status.DOWNLOADING -> {
                if (item.progress >= 0) {
                    h.progress.isIndeterminate = false
                    h.progress.progress = item.progress
                } else {
                    h.progress.isIndeterminate = true
                }
                val speed = item.speedBps
                val eta = if (speed > 0 && total > 0) (total - done) / speed else -1L
                val pct = if (item.progress >= 0) "${item.progress}%  •  " else ""
                h.info.text = "$pct$sizeText  •  ${fmtSpeed(speed)}" + if (eta >= 0) "  •  ETA ${fmtEta(eta)}" else ""
                h.action.text = "Pause"
            }
            Status.QUEUED -> {
                h.progress.isIndeterminate = false
                h.progress.progress = maxOf(item.progress, 0)
                h.info.text = "Waiting…"
                h.action.text = "Pause"
            }
            Status.PAUSED -> {
                h.progress.isIndeterminate = false
                h.progress.progress = maxOf(item.progress, 0)
                h.info.text = "Paused  •  $sizeText"
                h.action.text = "Resume"
            }
            Status.FAILED -> {
                h.progress.isIndeterminate = false
                h.progress.progress = maxOf(item.progress, 0)
                h.info.text = "Failed: ${item.error ?: "unknown error"}"
                h.action.text = "Retry"
            }
            Status.COMPLETED -> {
                h.progress.isIndeterminate = false
                h.progress.progress = 100
                h.info.text = "Completed  •  ${fmtSize(maxOf(total, done))}  •  Downloads/FastDownloader"
                h.action.text = "Open"
            }
        }

        h.action.setOnClickListener {
            when (item.status) {
                Status.DOWNLOADING, Status.QUEUED -> Downloads.pause(item.id)
                Status.PAUSED, Status.FAILED -> Downloads.resume(item.id)
                Status.COMPLETED -> onOpen(item)
            }
        }
        h.remove.setOnClickListener { onRemove(item) }
    }
}
