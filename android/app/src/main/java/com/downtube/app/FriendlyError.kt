package com.downtube.app

/**
 * Turns yt-dlp / network errors into something a person can act on.
 *
 * Shared by the download service and the playback path so the same problem
 * always reads the same way.
 */
object FriendlyError {

    fun from(throwable: Throwable?): String {
        val raw = (throwable?.message ?: throwable?.javaClass?.simpleName ?: "Unknown error")
            .replace("ERROR: ", "")
            .trim()

        return when {
            raw.contains("Sign in to confirm", true) ||
                raw.contains("Please sign in", true) ||
                raw.contains("Login required", true) ->
                "This site needs a login - open Login, sign in, then try again"

            raw.contains("Private video", true) ||
                raw.contains("This content is private", true) ->
                "This is private content - log in to the account that owns it"

            raw.contains("Unsupported URL", true) ->
                "This link is not supported"

            raw.contains("HTTP Error 404", true) || raw.contains("Video not found", true) ->
                "The video was not found - the link may have expired"

            raw.contains("HTTP Error 403", true) || raw.contains("Forbidden", true) ->
                "The site blocked this download - try logging in first"

            raw.contains("HTTP Error 401", true) ->
                "The site needs your account - open Login and sign in"

            raw.contains("HTTP Error 429", true) || raw.contains("Too Many Requests", true) ->
                "Too many requests - wait a couple of minutes and retry"

            raw.contains("HTTP Error 5", true) ->
                "The site is having problems right now - try again later"

            raw.contains("Sign in to view", true) || raw.contains("age-restricted", true) ->
                "This video is age restricted - log in via Login first"

            raw.contains("DRM", true) ->
                "This content is DRM protected and cannot be downloaded"

            raw.contains("Requested format is not available", true) ->
                "That quality is not available - pick another one"

            raw.contains("No space left", true) ->
                "Not enough storage space on this device"

            raw.contains("Permission denied", true) ->
                "Permission denied while saving the file"

            raw.contains("Unable to download", true) && raw.contains("network", true) ->
                "Network error - check your connection and retry"

            raw.contains("Connection", true) && raw.contains("refused", true) ->
                "Could not reach the site - check your connection"

            raw.contains("getaddrinfo", true) || raw.contains("Name or service not known", true) ->
                "DNS lookup failed - check your internet connection"

            raw.contains("Invalid URL", true) ->
                "That does not look like a valid link"

            raw.contains("Unable to parse", true) ->
                "Could not read the video information"

            raw.contains("interrupted", true) || raw.contains("Cancelled", true) ->
                "The download was interrupted"

            raw.isEmpty() -> "Something went wrong - try again"

            raw.length > 170 -> raw.take(167) + "..."
            else -> raw
        }
    }
}
