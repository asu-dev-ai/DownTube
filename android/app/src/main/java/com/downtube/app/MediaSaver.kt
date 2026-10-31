package com.downtube.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * yt-dlp writes into the app sandbox; this copies the result into the public
 * Downloads folder so the phone's gallery / file manager can see it.
 */
object MediaSaver {

    fun publish(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/downtube")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create the file in Downloads")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("could not open the file in Downloads")
            out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }

        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)

        return uri
    }

    fun mimeType(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
