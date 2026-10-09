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

    /**
     * v6.3.11: robust attribute extraction. Handles quoted ("..."/'...'),
     * unquoted (group-title=Sports), whitespace around '=', and any key case
     * (group-title / Group-Title / GROUP-TITLE). The old version only matched
     * key="..." exactly, so playlists with unquoted or differently-cased
     * group-title silently lost all categories.
     */
    private fun attr(line: String, key: String): String {
        // Only scan the attribute section (before the display name after
        // the last comma) so a channel name can't false-match.
        val attrs = line.substringBeforeLast(",")
        var searchFrom = 0
        val lower = attrs.lowercase()
        val keyLower = key.lowercase()
        while (true) {
            val i = lower.indexOf(keyLower, searchFrom)
            if (i < 0) return ""
            // Must be a standalone attribute name: preceded by start/space,
            // followed by optional spaces then '='.
            val beforeOk = i == 0 || attrs[i - 1].isWhitespace()
            var j = i + key.length
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (beforeOk && j < attrs.length && attrs[j] == '=') {
                var s = j + 1
                while (s < attrs.length && attrs[s].isWhitespace()) s++
                if (s >= attrs.length) return ""
                return when (attrs[s]) {
                    '"' -> {
                        val e = attrs.indexOf('"', s + 1)
                        if (e > s) attrs.substring(s + 1, e) else ""
                    }
                    '\'' -> {
                        val e = attrs.indexOf('\'', s + 1)
                        if (e > s) attrs.substring(s + 1, e) else ""
                    }
                    else -> {
                        // Unquoted: read until whitespace or comma.
                        var e = s
                        while (e < attrs.length && !attrs[e].isWhitespace() && attrs[e] != ',') e++
                        attrs.substring(s, e)
                    }
                }
            }
            searchFrom = i + 1
        }
    }
}
