package com.zynelabs.stb

/** A single TV channel from the active playlist source (Stalker / M3U / Xtream). */
data class Channel(
    val id: String,
    val number: String,
    val name: String,
    val cmd: String,
    val logo: String = "",
    val genreId: String = ""
) {
    /** v6.3: quality badge parsed from the channel name ("4K"/"FHD"/"HD"/"SD"/""). */
    val quality: String get() = QualityBadge.fromName(name)
}

/**
 * v6.3: parses a quality label from a channel name
 * (e.g. "Sky Sports 4K" → "4K", "beIN FHD" → "FHD").
 */
object QualityBadge {
    fun fromName(name: String): String {
        val t = name.uppercase()
        return when {
            "8K" in t || "UHD" in t || "4K" in t || "2160" in t -> "4K"
            "FHD" in t || "1080P" in t || "1080I" in t -> "FHD"
            "HD" in t || "720P" in t -> "HD"
            "SD" in t || "480P" in t || "576P" in t -> "SD"
            else -> ""
        }
    }
}
