package com.downtube.app

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.downtube.app.databinding.ItemDownloadBinding

class QueueAdapter(private val host: Host) : RecyclerView.Adapter<QueueAdapter.Holder>() {

    interface Host {
        fun onCancel(item: DownloadItem)
        fun onRetry(item: DownloadItem)
        fun onOpen(item: DownloadItem)
        fun onDelete(item: DownloadItem)
    }

    private var items: List<DownloadItem> = emptyList()

    fun submit(list: List<DownloadItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            val context = binding.root.context
            val running = item.status == DownloadStatus.RUNNING

            binding.title.text = item.title
            binding.statusText.text = statusLabel(item)
            binding.statusIcon.setImageResource(iconFor(item.status))

            val tone = ContextCompat.getColor(context, tintFor(item.status))
            binding.statusIcon.imageTintList = ColorStateList.valueOf(tone)
            binding.badge.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tone, 46))
            binding.statusText.setTextColor(tone)
            binding.statusText.setTextIsSelectable(item.status == DownloadStatus.FAILED)

            binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
            binding.progressBar.isIndeterminate = running && item.progress <= 0
            if (!binding.progressBar.isIndeterminate) binding.progressBar.progress = item.progress

            binding.cancelButton.visibility = if (running || item.status == DownloadStatus.QUEUED) View.VISIBLE else View.GONE
            binding.retryButton.visibility =
                if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELLED) View.VISIBLE else View.GONE
            binding.openButton.visibility = if (item.status == DownloadStatus.DONE && item.uri != null) View.VISIBLE else View.GONE
            binding.deleteButton.visibility = if (running) View.GONE else View.VISIBLE

            binding.cancelButton.setOnClickListener { host.onCancel(item) }
            binding.retryButton.setOnClickListener { host.onRetry(item) }
            binding.openButton.setOnClickListener { host.onOpen(item) }
            binding.deleteButton.setOnClickListener { host.onDelete(item) }
        }

        private fun statusLabel(item: DownloadItem): String {
            val context = binding.root.context
            return when (item.status) {
                DownloadStatus.QUEUED -> context.getString(R.string.status_pending)
                DownloadStatus.RUNNING -> {
                    val eta = if (item.etaSeconds > 0) {
                        context.getString(R.string.status_eta, item.etaSeconds / 60, item.etaSeconds % 60)
                    } else {
                        ""
                    }
                    context.getString(R.string.status_downloading, item.progress, eta)
                }
                DownloadStatus.DONE -> item.message ?: context.getString(R.string.status_done)
                DownloadStatus.FAILED -> item.message ?: context.getString(R.string.status_failed)
                DownloadStatus.CANCELLED -> context.getString(R.string.status_cancelled)
            }
        }

        private fun iconFor(status: DownloadStatus): Int = when (status) {
            DownloadStatus.QUEUED -> R.drawable.ic_status_queued
            DownloadStatus.RUNNING -> R.drawable.ic_status_running
            DownloadStatus.DONE -> R.drawable.ic_status_done
            DownloadStatus.FAILED -> R.drawable.ic_status_failed
            DownloadStatus.CANCELLED -> R.drawable.ic_status_cancelled
        }

        private fun tintFor(status: DownloadStatus): Int = when (status) {
            DownloadStatus.QUEUED -> R.color.status_queued
            DownloadStatus.RUNNING -> R.color.status_running
            DownloadStatus.DONE -> R.color.status_done
            DownloadStatus.FAILED -> R.color.status_failed
            DownloadStatus.CANCELLED -> R.color.status_cancelled
        }
    }
}
