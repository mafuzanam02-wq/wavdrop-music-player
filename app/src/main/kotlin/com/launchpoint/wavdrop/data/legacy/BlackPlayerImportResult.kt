package com.launchpoint.wavdrop.data.legacy

/**
 * Result returned by [BlackPlayerStatParser.parse].
 *
 * @property validRows      Rows that passed all validation rules.
 * @property invalidRows    Raw lines that failed parsing, preserved for diagnostic display.
 * @property totalPlayCount Sum of [BlackPlayerStatImportRow.playCount] across all valid rows.
 * @property totalSkipCount Sum of [BlackPlayerStatImportRow.skipCount] across all valid rows.
 */
data class BlackPlayerImportResult(
    val validRows: List<BlackPlayerStatImportRow>,
    val invalidRows: List<String>,
    val totalPlayCount: Long,
    val totalSkipCount: Long,
)
