package com.launchpoint.wavdrop.data.export

/** One playlist occurrence ready to serialize. [path] must already be a device file path. */
data class M3u8Entry(
    val path: String,
    val durationMs: Long,
    val artist: String,
    val title: String,
)

/**
 * Pure Extended-M3U serializer. UTF-8, no BOM, `\n` newlines, no path rewriting.
 * Metadata is flattened onto the single `#EXTINF` line so it can never add a directive or path line.
 */
object M3u8Formatter {
    const val HEADER = "#EXTM3U"

    fun isSerializablePath(path: String): Boolean =
        path.isNotBlank() && path.startsWith("/") && path.none(::isLineBreakingOrControl)

    fun sanitizeText(text: String): String =
        buildString(text.length) { text.forEach { append(if (isLineBreakingOrControl(it)) ' ' else it) } }.trim()

    fun format(entries: List<M3u8Entry>): String = buildString {
        append(HEADER).append('\n')
        for (e in entries) {
            require(isSerializablePath(e.path)) { "Path cannot be serialized safely" }
            append("#EXTINF:").append(durationSeconds(e.durationMs)).append(',').append(displayText(e)).append('\n')
            append(e.path).append('\n')
        }
    }

    fun toBytes(entries: List<M3u8Entry>): ByteArray = format(entries).toByteArray(Charsets.UTF_8)

    private fun durationSeconds(ms: Long): Long = if (ms <= 0L) -1L else (ms + 500L) / 1000L

    private fun displayText(e: M3u8Entry): String {
        val title = sanitizeText(e.title)
        val artist = sanitizeText(e.artist)
        val unknown = artist.isEmpty() || artist.equals("<unknown>", true) || artist.equals("Unknown Artist", true)
        return if (unknown) title else "$artist - $title"
    }

    private fun isLineBreakingOrControl(c: Char): Boolean =
        Character.isISOControl(c) || c == ' ' || c == ' '
}
