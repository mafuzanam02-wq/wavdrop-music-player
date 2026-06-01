package com.launchpoint.wavdrop.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class AudioPermissionStatus {
    NotRequested,
    Denied,
    PermanentlyDenied,
    Granted,
}

/** Runtime permission string appropriate for the running API level. */
val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

fun Context.hasAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
