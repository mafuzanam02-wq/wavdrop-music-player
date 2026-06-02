package com.launchpoint.wavdrop.data.legacy

/**
 * A single successfully parsed row from a BlackPlayer EX .bpstat export file.
 *
 * Wire format (8 semicolon-separated fields, no escaping):
 *   playCount;skipCount;title;artist;album;filePath;dateAddedMs;lastPlayedMs
 *
 * Semicolons inside string field values are not supported by the format
 * and will cause that row to be rejected by [BlackPlayerStatParser].
 */
data class BlackPlayerStatImportRow(
    val playCount: Int,
    val skipCount: Int,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val dateAddedMs: Long,
    val lastPlayedMs: Long,
)
