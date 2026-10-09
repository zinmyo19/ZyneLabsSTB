package com.zynelabs.stb

/**
 * v6.3: minimal M3U/M3U8 playlist parser.
 * Handles #EXTINF lines with tvg-logo / group-title attributes,
 * followed by the stream URL on the next non-comment line.
 * Lines without a preceding #EXTINF are kept with the URL as name.
 */
object M3uParser {

    data class Entry(
        val name: String,
        val url: String,
        val logo: String,
        val group: String
    )

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var name = ""
        var logo = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
            } else if (line.startsWith("#")) {
                continue // other directives / comments
            } else {
                // stream URL
                out.add(
                    Entry(
                        name = name.ifBlank { line },
                        url = line,
                        logo = logo,
                        group = group
                    )
                )
                name = ""
                logo = ""
                group = ""
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val s = i + key.length + 2
        val e = line.indexOf('"', s)
        return if (e > s) line.substring(s, e) else ""
    }
}
