package com.launchpoint.wavdrop.ui.screen.settings

/**
 * Document-picker MIME configuration for WavDrop backup files.
 *
 * Android providers report arbitrary MIME types for `.wdbk` (often `application/octet-stream`, sometimes
 * `application/zip` or a provider-specific type) and legacy `.json` backups (`application/json`, `text/plain`, …).
 * A narrow filter would hide valid files on some providers, so the open picker is NOT narrowed; every selected
 * document is validated strictly by CONTENT after selection (see `WavdropBackupDocumentReader`), never by MIME or
 * extension.
 */
object BackupPickerTypes {
    /** Open picker for Restore: all documents; content validation decides. */
    val OPEN_BACKUP_MIME_TYPES: Array<String> = arrayOf("*/*")

    /**
     * Create-document MIME for new `.wdbk` files. A generic binary type keeps providers from appending `.zip` or
     * `.json`; the `.wdbk` name suggested to the picker is authoritative for newly created backups.
     */
    const val CREATE_BACKUP_MIME: String = "application/octet-stream"
}
