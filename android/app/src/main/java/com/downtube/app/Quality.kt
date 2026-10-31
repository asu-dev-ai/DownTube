package com.downtube.app

/**
 * Download presets. [format] is a yt-dlp -f selector.
 */
enum class Quality(val label: String, val format: String, val audio: Boolean) {
    BEST("Best available", "bv*+ba/b", false),
    P1080("1080p", "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", false),
    P720("720p", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", false),
    P480("480p", "bv*[height<=480]+ba/b[height<=480]/bv*+ba/b", false),
    P360("360p", "bv*[height<=360]+ba/b[height<=360]/bv*+ba/b", false),
    MP3("MP3 (audio only)", "ba/b", true),
}
