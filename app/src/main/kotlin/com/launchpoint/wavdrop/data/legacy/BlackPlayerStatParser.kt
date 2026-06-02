package com.launchpoint.wavdrop.data.legacy

/**
 * Parses the plain-text content of a BlackPlayer EX .bpstat statistics export file.
 *
 * ## Format
 * Each line holds exactly 8 semicolon-separated fields with no quoting or escaping:
 * ```
 * playCount;skipCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs
 * ```
 * Example:
 * ```
 * 148;2;23;Wilfred;Everything We Need;/storage/emulated/0/Music/example.mp3;1759066940607;1779810940727
 * ```
 *
 * ## Validation rules (a line is rejected if any rule fails)
 * - Exactly 8 semicolon-separated fields (semicolons inside values are not supported).
 * - playCount and skipCount must parse as non-negative Int.
 * - dateAddedMs and lastPlayedMs must parse as non-negative Long.
 * - title, artist, album, and filePath are accepted as-is (may be empty strings).
 *
 * ## Future matching strategy (import not yet wired to Room)
 * When imported rows are applied to the Wavdrop library the match priority will be:
 * 1. Exact filePath — compare against the `data` part of the song's `content://` URI.
 * 2. Title + artist + album — case-insensitive, trimmed string equality.
 * 3. Fuzzy title fallback (Levenshtein) — planned, not yet implemented.
 *
 * Existing Wavdrop stats are NEVER silently overwritten; imports must go through a
 * preview/confirm step before being applied to [com.launchpoint.wavdrop.data.repository.StatsRepository].
 */
object BlackPlayerStatParser {

    private const val FIELD_COUNT = 8

    fun parse(content: String): BlackPlayerImportResult {
        val validRows   = mutableListOf<BlackPlayerStatImportRow>()
        val invalidRows = mutableListOf<String>()

        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val row = parseLine(line)
                if (row != null) validRows.add(row) else invalidRows.add(line)
            }

        return BlackPlayerImportResult(
            validRows      = validRows,
            invalidRows    = invalidRows,
            totalPlayCount = validRows.sumOf { it.playCount.toLong() },
            totalSkipCount = validRows.sumOf { it.skipCount.toLong() },
        )
    }

    private fun parseLine(line: String): BlackPlayerStatImportRow? {
        val fields = line.split(";")
        if (fields.size != FIELD_COUNT) return null

        val playCount    = fields[0].toIntOrNull()  ?: return null
        val skipCount    = fields[1].toIntOrNull()  ?: return null
        val title        = fields[2]
        val artist       = fields[3]
        val album        = fields[4]
        val filePath     = fields[5]
        val dateAddedMs  = fields[6].toLongOrNull() ?: return null
        val lastPlayedMs = fields[7].toLongOrNull() ?: return null

        if (playCount < 0 || skipCount < 0) return null
        if (dateAddedMs < 0 || lastPlayedMs < 0) return null

        return BlackPlayerStatImportRow(
            playCount    = playCount,
            skipCount    = skipCount,
            title        = title,
            artist       = artist,
            album        = album,
            filePath     = filePath,
            dateAddedMs  = dateAddedMs,
            lastPlayedMs = lastPlayedMs,
        )
    }
}
