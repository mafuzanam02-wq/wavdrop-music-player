package com.launchpoint.wavdrop.data.backup.wdbk

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/** [BackupFileHandle] over a SAF/content Uri (manual export destination). */
class UriBackupHandle(private val context: Context, private val uri: Uri) : BackupFileHandle {
    override fun openOutput(mode: String): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri, mode) }.getOrNull()

    override fun openInput(): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    override fun delete(): Boolean = false // the user's chosen destination is never deleted by Wavdrop
}

/** [BackupFileHandle] over a [DocumentFile] inside the user's backup folder. */
class DocumentFileBackupHandle(
    private val context: Context,
    private val file: DocumentFile,
) : BackupFileHandle {
    override fun openOutput(mode: String): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(file.uri, mode) }.getOrNull()

    override fun openInput(): InputStream? =
        runCatching { context.contentResolver.openInputStream(file.uri) }.getOrNull()

    override fun delete(): Boolean = runCatching { file.delete() }.getOrDefault(false)
}

/**
 * [BackupFolder] over a SAF tree. Child lookup scans the directory LISTING instead of `DocumentFile.findFile`,
 * which many providers answer incorrectly — a miss makes `createFile` run again and the provider appends " (1)",
 * producing duplicate backups.
 */
class DocumentFileBackupFolder(
    private val context: Context,
    private val folder: DocumentFile,
) : BackupFolder {
    override fun findChild(name: String): BackupFileHandle? =
        folder.listFiles().firstOrNull { it.name == name }?.let { DocumentFileBackupHandle(context, it) }

    override fun createFile(mimeType: String, name: String): BackupFileHandle? =
        folder.createFile(mimeType, name)?.let { DocumentFileBackupHandle(context, it) }
}
