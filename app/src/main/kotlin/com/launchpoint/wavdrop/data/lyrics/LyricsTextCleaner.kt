package com.launchpoint.wavdrop.data.lyrics

object LyricsTextCleaner {

    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { line ->
                line.replace(LEADING_LRC_TIMESTAMPS, "").trimEnd()
            }
            .trim()

        return normalized.takeIf { it.isNotBlank() }
    }

    private val LEADING_LRC_TIMESTAMPS =
        Regex("""^\s*(?:\[\d{1,2}:\d{2}(?:\.\d{1,3})?])+""")
}
