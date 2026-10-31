package com.downtube.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Thin wrapper around youtubedl-android.
 *
 * Initialisation unpacks python/ffmpeg on first use, so it must never run on the
 * main thread.
 */
object Engine {

    /** 120 bytes of the title max, so long titles cannot blow the 255 byte limit. */
    private const val OUTPUT_TEMPLATE = "%(title).120B [%(id)s].%(ext)s"

    @Volatile
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        YoutubeDL.init(context.applicationContext)
        FFmpeg.init(context.applicationContext)
        ready = true
    }

    /**
     * Runs yt-dlp and blocks until the download finishes.
     *
     * @throws YoutubeDL.CanceledException when [cancel] was called for this item.
     */
    fun run(
        context: Context,
        item: DownloadItem,
        workDir: File,
        onProgress: (Float, Long) -> Unit,
    ) {
        val request = YoutubeDLRequest(item.url)
        request
            .addOption("--continue")
            .addOption("--no-mtime")
            .addOption("--retries", "10")
            .addOption("--fragment-retries", "10")
            .addOption("-o", File(workDir, OUTPUT_TEMPLATE).absolutePath)
            .addOption("-f", item.quality.format)

        if (item.playlist) request.addOption("--yes-playlist") else request.addOption("--no-playlist")

        if (item.quality.audio) {
            request
                .addOption("-x")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "192K")
        }

        Cookies.file(context)?.let { request.addOption("--cookies", it.absolutePath) }

        YoutubeDL.execute(request, item.id) { progress, eta, _ ->
            onProgress(progress, eta)
        }
    }

    /** Kills the running yt-dlp process for this queue entry. */
    fun cancel(itemId: String) {
        runCatching { YoutubeDL.destroyProcessById(itemId) }
    }

    /** Pulls the newest nightly yt-dlp build; keeps the app working over time. */
    fun updateYtDlp(context: Context): String {
        init(context)
        val status = YoutubeDL.updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date"
            null -> "yt-dlp update finished"
        }
    }

    fun version(context: Context): String =
        runCatching { YoutubeDL.version(context) }.getOrNull().orEmpty()
}
