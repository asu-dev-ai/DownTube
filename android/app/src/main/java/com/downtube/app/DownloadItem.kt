package com.downtube.app

import android.net.Uri

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * One entry in the download queue. Mutable on purpose: the service updates it
 * in place and the list re-renders from a snapshot.
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val quality: Quality,
    val playlist: Boolean,
    var title: String = url,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var etaSeconds: Long = 0,
    var message: String? = null,
    var uri: Uri? = null,
    var outputName: String? = null,
)
