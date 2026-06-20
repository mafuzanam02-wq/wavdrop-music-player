package com.launchpoint.wavdrop.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun AudioPermissionGate(
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
    var permissionStatus by remember {
        mutableStateOf(
            if (context.hasAudioPermission()) AudioPermissionStatus.Granted
            else AudioPermissionStatus.NotRequested,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        permissionStatus = when {
            isGranted -> AudioPermissionStatus.Granted
            context.shouldShowAudioPermissionRationale() -> AudioPermissionStatus.Denied
            else -> AudioPermissionStatus.PermanentlyDenied
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (context.hasAudioPermission()) {
                    permissionStatus = AudioPermissionStatus.Granted
                } else if (permissionStatus == AudioPermissionStatus.Granted) {
                    permissionStatus = AudioPermissionStatus.NotRequested
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionStatus) {
        if (permissionStatus == AudioPermissionStatus.Granted) {
            currentOnPermissionGranted()
        }
    }

    when (permissionStatus) {
        AudioPermissionStatus.Granted -> content()
        AudioPermissionStatus.NotRequested -> AllowMusicAccessContent(
            modifier = modifier,
            onRequestPermission = { permissionLauncher.launch(audioPermission) },
        )
        AudioPermissionStatus.Denied -> AudioPermissionDeniedContent(
            modifier = modifier,
            onRetry = { permissionLauncher.launch(audioPermission) },
        )
        AudioPermissionStatus.PermanentlyDenied -> AudioPermissionBlockedContent(
            modifier = modifier,
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

@Composable
private fun AllowMusicAccessContent(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PermissionCenteredColumn(modifier) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Allow music access",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Wavdrop needs access to audio files on this device so it can build your local music library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequestPermission) {
            Text("Allow access")
        }
    }
}

@Composable
private fun AudioPermissionDeniedContent(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PermissionCenteredColumn(modifier) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Music access denied",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Wavdrop cannot scan your local music without audio file access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun AudioPermissionBlockedContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PermissionCenteredColumn(modifier) {
        Icon(
            imageVector = Icons.Default.FolderOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Music access blocked",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open Android Settings, then go to Permissions and allow Music & audio for Wavdrop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}

@Composable
private fun PermissionCenteredColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = { content() },
    )
}

private fun Context.shouldShowAudioPermissionRationale(): Boolean =
    findActivity()?.let { activity ->
        ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
    } == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
