package com.launchpoint.wavdrop.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBluetoothScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val resumeBehavior by viewModel.resumeBehaviorSettings.collectAsStateWithLifecycle()

    val context      = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    // Refresh the battery-optimization status whenever the user returns from system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations =
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        isIgnoringBatteryOptimizations =
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth & Headphones") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item { SectionHeader("Audio Output") }
            item {
                ToggleSettingsRow(
                    title           = "Pause when audio output disconnects",
                    subtitle        = "Pause playback when headphones or Bluetooth audio disconnects.",
                    checked         = resumeBehavior.pauseOnAudioDisconnect,
                    onCheckedChange = viewModel::setPauseOnAudioDisconnect,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Bluetooth Auto Resume") }
            item {
                SettingsMessageRow(
                    message = "Choose how Wavdrop behaves when Bluetooth reconnects.",
                )
            }
            HeadphoneResumeMode.entries.forEach { mode ->
                item {
                    ScanModeRow(
                        title    = mode.displayName,
                        subtitle = mode.description,
                        selected = resumeBehavior.bluetoothResumeMode == mode,
                        onClick  = { viewModel.setBluetoothResumeMode(mode) },
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("Wired Headphone Auto Resume") }
            item {
                SettingsMessageRow(
                    message = "Choose how Wavdrop behaves when wired headphones reconnect.",
                )
            }
            HeadphoneResumeMode.entries.forEach { mode ->
                item {
                    ScanModeRow(
                        title    = mode.displayName,
                        subtitle = mode.description,
                        selected = resumeBehavior.wiredResumeMode == mode,
                        onClick  = { viewModel.setWiredResumeMode(mode) },
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("Background Playback Reliability") }
            item {
                SettingsMessageRow(
                    message = "For the best Bluetooth and wired auto-resume experience, allow Wavdrop " +
                        "to run without battery restrictions. Some phones may still stop apps that are " +
                        "closed from Recent Apps.",
                )
            }
            item {
                if (isIgnoringBatteryOptimizations) {
                    ClickableSettingsRow(
                        title    = "Background playback allowed",
                        subtitle = "Wavdrop is excluded from battery optimization on this device.",
                        onClick  = {
                            // Let the user review or revoke the exemption.
                            try {
                                batteryOptLauncher.launch(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            } catch (_: ActivityNotFoundException) {
                            } catch (_: SecurityException) { }
                        },
                    )
                } else {
                    ClickableSettingsRow(
                        title    = "Allow background playback",
                        subtitle = "Open system settings to improve playback and auto-resume reliability.",
                        onClick  = {
                            val pkg = context.packageName
                            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$pkg")
                            }
                            var launched = false
                            try {
                                batteryOptLauncher.launch(direct)
                                launched = true
                            } catch (_: ActivityNotFoundException) {
                            } catch (_: SecurityException) { }
                            if (!launched) {
                                try {
                                    batteryOptLauncher.launch(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                } catch (_: ActivityNotFoundException) {
                                } catch (_: SecurityException) { }
                            }
                        },
                    )
                }
            }
        }
    }
}
