package com.launchpoint.wavdrop.data.lyrics

/** Timed lyric lines plus the same lyric as timestamp-free text. */
data class ParsedLrc(
    val lines: List<SyncedLyricsLine>,
    val plainText: String,
)

/**
 * Pure `.lrc` parser (no Android dependencies).
 *
 * Policies:
 * - Timestamps: `[m:ss]`, `[mm:ss.f]`, `[mm:ss.ff]`, `[mm:ss.fff]`; minutes may be any number of
 *   digits, seconds must be 0..59, the fraction is read as a decimal (`.3` = 300 ms, not 3 ms).
 * - Several leading timestamps on one line each create a timed entry; the lyric appears once in
 *   [ParsedLrc.plainText].
 * - `[offset:N]` (milliseconds, may be negative) is ADDED to every timestamp; the last valid offset
 *   tag in the file wins; results below zero clamp to 0.
 * - Other `[key:value]` metadata tags (ar, ti, al, by, …) are ignored and never shown as lyrics.
 * - Timed entries are ordered by effective time; equal times keep source order (stable sort).
 * - A timed line with empty text is kept as a timeline boundary (instrumental gap).
 * - Untimed ordinary lines never get a fabricated time; they stay in [ParsedLrc.plainText] only.
 * - Malformed timestamps (e.g. `[00:99.00]`) are dropped without failing the line.
 * - Returns null unless at least one timed entry has non-blank text.
 */
object LrcParser {

    fun parse(raw: String?): ParsedLrc? {
        if (raw.isNullOrBlank()) return null

        var offsetMs = 0L
        val timed = ArrayList<SyncedLyricsLine>()
        val plain = ArrayList<String>()

        raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { sourceLine ->
                var rest = sourceLine.trim()
                val times = ArrayList<Long>()
                var metadataLine = false

                while (true) {
                    val match = LEADING_TAG.find(rest) ?: break
                    val tag = match.groupValues[1].trim()
                    val timestamp = parseTimestampMs(tag)
                    when {
                        timestamp != null -> times += timestamp
                        OFFSET_TAG.matches(tag) -> {
                            OFFSET_TAG.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull()
                                ?.let { offsetMs = it }
                            metadataLine = true
                        }
                        METADATA_TAG.matches(tag) -> metadataLine = true
                        MALFORMED_TIMESTAMP.matches(tag) -> Unit // dropped, rest of the line is kept
                        else -> break // e.g. "[Chorus]": ordinary lyric text
                    }
                    rest = rest.substring(match.range.last + 1).trimStart()
                    if (metadataLine) break
                }

                if (metadataLine && times.isEmpty()) return@forEach
                val text = rest.trim()
                if (times.isNotEmpty()) {
                    times.forEach { timed += SyncedLyricsLine(timeMs = it, text = text) }
                }
                plain += text
            }

        val lines = timed
            .map { it.copy(timeMs = (it.timeMs + offsetMs).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs } // stable: equal timestamps keep source order
        if (lines.none { it.text.isNotBlank() }) return null

        return ParsedLrc(lines = lines, plainText = plain.collapseBlankRuns())
    }

    private fun parseTimestampMs(tag: String): Long? {
        val match = TIMESTAMP.matchEntire(tag) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        if (seconds !in 0..59) return null
        val fractionDigits = match.groupValues[3]
        val fraction = if (fractionDigits.isEmpty()) 0L else fractionDigits.padEnd(3, '0').toLong()
        return minutes * 60_000L + seconds * 1_000L + fraction
    }

    private fun List<String>.collapseBlankRuns(): String {
        val out = ArrayList<String>()
        forEach { line ->
            if (line.isBlank() && (out.isEmpty() || out.last().isBlank())) return@forEach
            out += line
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    private val LEADING_TAG = Regex("""^\[([^\]]*)]""")
    private val TIMESTAMP = Regex("""^(\d+):(\d{1,2})(?:\.(\d{1,3}))?$""")
    private val MALFORMED_TIMESTAMP = Regex("""^\d+:\d.*$""")
    private val OFFSET_TAG = Regex("""^(?i:offset)\s*:\s*([+-]?\d+)$""")
    private val METADATA_TAG = Regex("""^[A-Za-z][A-Za-z0-9_]*\s*:.*$""")
}
