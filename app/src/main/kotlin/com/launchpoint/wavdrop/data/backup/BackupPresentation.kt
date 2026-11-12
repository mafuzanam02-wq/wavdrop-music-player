package com.launchpoint.wavdrop.data.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * P5-REL-05. Pure presentation rules for an already-verified [WavdropBackup] (the parser / WDBK reader has rejected anything
 * whose integrity failed before a [WavdropBackup] exists). Nothing here touches integrity checking.
 */
internal object BackupPresentation {

    const val UNKNOWN_DATE = "Unknown"

    const val LEGACY_UNSIGNED_WARNING =
        "Older backup format. It can still be restored — create a new backup to add integrity protection."
    const val LEGACY_SIGNED_WARNING =
        "Older backup format. It can still be restored — create a new backup to use the current format."

    /**
     * The genuine embedded export time. v2 / WDBK carry [WavdropBackup.exportedAtMs] (covered by the v2 fingerprint / manifest) and
     * take precedence; legacy v1 carries an ISO-8601 [WavdropBackup.exportedAt]. A non-positive or unparseable value is "missing".
     * The file's last-modified time is never consulted here.
     */
    fun exportTimestampMillis(backup: WavdropBackup): Long? {
        backup.exportedAtMs?.takeIf { it > 0 }?.let { return it }
        return parseIso(backup.exportedAt)
    }

    /** `d MMM yyyy, HH:mm` in [zone], the verification screen's established format, or [UNKNOWN_DATE]. */
    fun formatExportDate(backup: WavdropBackup, zone: ZoneId = ZoneId.systemDefault()): String =
        exportTimestampMillis(backup)?.let { formatMillis(it, zone) } ?: UNKNOWN_DATE

    /** Desktop backups carry an ISO string only; an unparseable non-blank string is shown as written, blank is Unknown. */
    fun formatDesktopExportDate(exportedAt: String, zone: ZoneId = ZoneId.systemDefault()): String =
        parseIso(exportedAt)?.let { formatMillis(it, zone) } ?: exportedAt.trim().ifEmpty { UNKNOWN_DATE }

    fun formatMillis(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching {
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))
        }.getOrDefault(UNKNOWN_DATE)

    /**
     * Compatibility warning for genuinely old (v1) backups only. v2 JSON and WDBK are the current formats; their integrity is the v2
     * fingerprint / WDBK manifest, so a missing legacy `payloadSha256` is expected and not a warning.
     */
    fun legacyFormatWarning(backup: WavdropBackup): String? = when {
        backup.sourceVersion != BackupFormatVersion.V1 -> null
        backup.payloadSha256 == null -> LEGACY_UNSIGNED_WARNING
        else -> LEGACY_SIGNED_WARNING
    }

    private fun parseIso(value: String): Long? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() }
}
