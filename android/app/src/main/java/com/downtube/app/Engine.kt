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
     * Throws [com.yausername.youtubedl_android.YoutubeDL.CanceledException] when
     * [YoutubeDL.destroyProcessById] is called for [processId].
     */
    fun run(
        url: String,
        quality: Quality,
        workDir: File,
        processId: String,
        onProgress: (Float, Long) -> Unit,
    ) {
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("-o", File(workDir, OUTPUT_TEMPLATE).absolutePath)
            .addOption("-f", quality.format)

        if (quality.audio) {
            request
                .addOption("-x")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "192K")
        }

        YoutubeDL.execute(request, processId) { progress, eta, _ ->
            onProgress(progress, eta)
        }
    }
}
