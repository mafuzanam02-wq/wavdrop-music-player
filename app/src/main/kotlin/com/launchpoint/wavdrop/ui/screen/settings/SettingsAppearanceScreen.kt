package com.launchpoint.wavdrop.ui.screen.settings

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(
    onNavigateBack: () -> Unit,
    onHomeCustomizationClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val appIconChoice    by viewModel.appIconChoice.collectAsStateWithLifecycle()
    val themeMode        by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor      by viewModel.accentColor.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.iconChangeEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
            item { SectionHeader("Home") }
            item {
                ClickableSettingsRow(
                    title    = "Home Sections",
                    subtitle = "Choose which sections appear on your Home screen.",
                    onClick  = onHomeCustomizationClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Theme Mode") }
            ThemeMode.entries.forEach { mode ->
                item {
                    IconChoiceRow(
                        name     = mode.displayName,
                        selected = themeMode == mode,
                        onClick  = { viewModel.setThemeMode(mode) },
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("Accent Color") }
            AccentColor.entries.forEach { color ->
                item {
                    IconChoiceRow(
                        name     = color.displayName,
                        selected = accentColor == color,
                        onClick  = { viewModel.setAccentColor(color) },
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("App Icon") }
            item {
                SettingsMessageRow(
                    message = "App icon changes depend on your phone's launcher. On some devices, " +
                        "the selected icon may not appear immediately or may not update visually.",
                )
            }
            AppIconChoice.entries.forEach { choice ->
                item {
                    IconChoiceRow(
                        name     = choice.displayName,
                        selected = appIconChoice == choice,
                        onClick  = { viewModel.setAppIcon(choice) },
                    )
                }
            }
        }
    }
}
