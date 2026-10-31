package com.downtube.app

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.net.URI

/**
 * Saves the cookies of a page the user logged into inside the in-app browser as
 * a Netscape cookie file, which yt-dlp understands via --cookies.
 */
object Cookies {

    private const val FILE_NAME = "cookies.txt"
    private const val MONTH_SECONDS = 60L * 60L * 24L * 30L

    fun file(context: Context): File? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun exists(context: Context): Boolean = file(context) != null

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    /** @return how many cookies were written, 0 when the page had none. */
    fun save(context: Context, url: String): Int {
        val raw = CookieManager.getInstance().getCookie(url).orEmpty()
        if (raw.isBlank()) return 0

        val host = runCatching { URI(url).host }.getOrNull() ?: return 0
        val domain = if (host.startsWith("www.")) host.removePrefix("www.") else host
        val expiry = System.currentTimeMillis() / 1000 + MONTH_SECONDS

        val out = StringBuilder("# Netscape HTTP Cookie File\n")
        var count = 0
        raw.split(";").forEach { part ->
            val pair = part.trim()
            if (!pair.contains("=")) return@forEach
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=").trim()
            if (name.isEmpty()) return@forEach
            out.append('.').append(domain).append("\tTRUE\t/\tFALSE\t")
                .append(expiry).append('\t').append(name).append('\t').append(value).append('\n')
            count++
        }

        if (count == 0) return 0
        File(context.filesDir, FILE_NAME).writeText(out.toString())
        return count
    }
}
