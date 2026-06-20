package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWrappedScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val showMilestoneCelebrations    by viewModel.showMilestoneCelebrations.collectAsStateWithLifecycle()
    val wrappedUseArtworkBackgrounds by viewModel.wrappedUseArtworkBackgrounds.collectAsStateWithLifecycle()
    val wrappedBackgroundIntensity   by viewModel.wrappedBackgroundIntensity.collectAsStateWithLifecycle()
    val wrappedFallbackTheme         by viewModel.wrappedFallbackTheme.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wrapped Appearance") },
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
            item { SectionHeader("Appearance") }
            item {
                ToggleSettingsRow(
                    title           = "Show milestone celebrations",
                    subtitle        = "Display milestone summaries inside yearly Wrapped recaps.",
                    checked         = showMilestoneCelebrations,
                    onCheckedChange = viewModel::setShowMilestoneCelebrations,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Use artwork backgrounds",
                    subtitle        = "Use your music artwork as atmospheric card backgrounds when available.",
                    checked         = wrappedUseArtworkBackgrounds,
                    onCheckedChange = viewModel::setWrappedUseArtworkBackgrounds,
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Background intensity") }
            item {
                SettingsMessageRow(
                    message = "Control how bold Wrapped backgrounds feel.",
                )
            }
            WrappedBackgroundIntensity.entries.forEach { intensity ->
                item {
                    ScanModeRow(
                        title    = intensity.displayName,
                        subtitle = intensity.description,
                        selected = wrappedBackgroundIntensity == intensity,
                        onClick  = { viewModel.setWrappedBackgroundIntensity(intensity) },
                    )
                }
            }
            item { SectionDivider() }
            item { SectionHeader("Fallback theme") }
            item {
                SettingsMessageRow(
                    message = "Choose the visual mood used when artwork is unavailable.",
                )
            }
            WrappedFallbackTheme.entries.forEach { theme ->
                item {
                    ScanModeRow(
                        title    = theme.displayName,
                        subtitle = theme.description,
                        selected = wrappedFallbackTheme == theme,
                        onClick  = { viewModel.setWrappedFallbackTheme(theme) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
