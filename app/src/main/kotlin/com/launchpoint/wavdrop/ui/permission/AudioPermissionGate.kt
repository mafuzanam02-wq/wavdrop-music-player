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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AudioPermissionGate(
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
    val permissionViewModel: AudioPermissionViewModel = hiltViewModel()
    val hasEverGranted by permissionViewModel.hasEverGranted.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(context.hasAudioPermission()) }
    var lastRequest by remember { mutableStateOf<AudioPermissionRequestOutcome?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        lastRequest = AudioPermissionResolver.outcomeOf(isGranted, context.shouldShowAudioPermissionRationale())
        hasPermission = context.hasAudioPermission()
    }

    // Re-read the real permission bit on every resume (returning from Android Settings, or after a revoke).
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = context.hasAudioPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            permissionViewModel.recordGranted() // idempotent, at most one write
            currentOnPermissionGranted()
        }
    }

    // Until the persisted history is read, a missing permission must not flash the first-run screen.
    val everGrantedKnown = hasEverGranted
    if (!hasPermission && everGrantedKnown == null) {
        Spacer(modifier.fillMaxSize())
        return
    }
    val permissionStatus = AudioPermissionResolver.resolve(hasPermission, everGrantedKnown == true, lastRequest)

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
        AudioPermissionStatus.Revoked -> AudioPermissionRevokedContent(
            modifier = modifier,
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

@Composable
private fun AudioPermissionRevokedContent(
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
            text = AudioPermissionCopy.REVOKED_TITLE,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = AudioPermissionCopy.REVOKED_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}

/** User-facing copy that must stay distinct between first run, blocked and revoked. */
object AudioPermissionCopy {
    const val FIRST_RUN_TITLE = "Allow music access"
    const val BLOCKED_TITLE = "Music access blocked"
    const val REVOKED_TITLE = "Music access was turned off"
    const val REVOKED_BODY =
        "Wavdrop no longer has permission to read music on this device. Your existing library data has been kept."
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
            text = AudioPermissionCopy.FIRST_RUN_TITLE,
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
            text = AudioPermissionCopy.BLOCKED_TITLE,
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
