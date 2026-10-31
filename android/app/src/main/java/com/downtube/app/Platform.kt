package com.downtube.app

/**
 * Detects the platform behind a link so the UI can label it and choose sensible
 * defaults (audio-friendly sites default to MP3-friendly downloads).
 */
enum class Platform(val label: String, val audioFriendly: Boolean) {
    YOUTUBE("YouTube", true),
    INSTAGRAM("Instagram", false),
    FACEBOOK("Facebook", false),
    TIKTOK("TikTok", true),
    TWITTER("Twitter / X", false),
    REDDIT("Reddit", false),
    PINTEREST("Pinterest", false),
    SNAPCHAT("Snapchat", false),
    SOUNDCLOUD("SoundCloud", true),
    VIMEO("Vimeo", true),
    DAILYMOTION("Dailymotion", true),
    TWITCH("Twitch", false),
    TUMBLR("Tumblr", false),
    BILIBILI("Bilibili", false),
    ;

    companion object {
        /** @return the platform for this URL, or null when it is not recognised. */
        fun detect(url: String): Platform? {
            val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
            if (host.isEmpty()) return null
            return when {
                "youtube.com" in host || "youtu.be" in host -> YOUTUBE
                "instagram.com" in host -> INSTAGRAM
                "facebook.com" in host || "fb.watch" in host -> FACEBOOK
                "tiktok.com" in host || "vm.tiktok" in host -> TIKTOK
                "twitter.com" in host || "x.com" in host -> TWITTER
                "reddit.com" in host || "redd.it" in host -> REDDIT
                "pinterest." in host || "pin.it" in host -> PINTEREST
                "snapchat.com" in host -> SNAPCHAT
                "soundcloud.com" in host -> SOUNDCLOUD
                "vimeo.com" in host -> VIMEO
                "dailymotion.com" in host || "dai.ly" in host -> DAILYMOTION
                "twitch.tv" in host -> TWITCH
                "tumblr.com" in host -> TUMBLR
                "bilibili.com" in host -> BILIBILI
                else -> null
            }
        }
    }
}
