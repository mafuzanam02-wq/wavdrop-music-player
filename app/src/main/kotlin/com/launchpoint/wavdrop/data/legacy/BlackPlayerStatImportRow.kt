package com.launchpoint.wavdrop.data.legacy

/**
 * A single successfully parsed row from a BlackPlayer EX .bpstat export file.
 *
 * Wire format (8 semicolon-separated fields, no escaping):
 *   playCount;periodPlayCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs
 *
 * Field 1 ([playCount]) is the main play count WavDrop imports. Field 2 ([periodPlayCount]) is a PERIOD play count
 * (a secondary play aggregate); it is NOT a skip count. It is parsed and shown for honest inspection only: it is never
 * imported into WavDrop's skipCount, never added to [playCount], never turned into listening time and never creates events.
 *
 * Semicolons inside string field values are not supported by the format
 * and will cause that row to be rejected by [BlackPlayerStatParser].
 */
data class BlackPlayerStatImportRow(
    val playCount: Int,
    val periodPlayCount: Int,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val dateAddedMs: Long,
    val lastPlayedMs: Long,
)
