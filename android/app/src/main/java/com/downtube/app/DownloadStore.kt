package com.downtube.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory queue shared by the UI and [DownloadService].
 * Every mutation notifies listeners; listeners are responsible for hopping to
 * the main thread.
 */
object DownloadStore {

    fun interface Listener {
        fun onDownloadsChanged()
    }

    private val items = CopyOnWriteArrayList<DownloadItem>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()

    fun all(): List<DownloadItem> = items.toList()

    fun find(id: String): DownloadItem? = items.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        items.add(item)
        notifyChanged()
    }

    fun update(id: String, block: (DownloadItem) -> Unit): Boolean {
        val item = find(id) ?: return false
        block(item)
        notifyChanged()
        return true
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        notifyChanged()
    }

    fun clearFinished() {
        items.removeAll { it.status == DownloadStatus.DONE || it.status == DownloadStatus.CANCELLED }
        notifyChanged()
    }

    /** Marks the next queued item as running and hands it to the worker. */
    fun takeNext(): DownloadItem? = synchronized(lock) {
        val next = items.firstOrNull { it.status == DownloadStatus.QUEUED } ?: return@synchronized null
        next.status = DownloadStatus.RUNNING
        next.message = null
        notifyChanged()
        next
    }

    fun running(): DownloadItem? = items.firstOrNull { it.status == DownloadStatus.RUNNING }

    fun hasWork(): Boolean = items.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }

    fun pendingCount(): Int = items.count { it.status == DownloadStatus.QUEUED }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            runCatching { listener.onDownloadsChanged() }
        }
    }
}
