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
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.ui.theme.AmberGoldPrimary
import com.launchpoint.wavdrop.ui.theme.CleanPurplePrimary
import com.launchpoint.wavdrop.ui.theme.CrimsonRedPrimary
import com.launchpoint.wavdrop.ui.theme.DeepTealPrimary
import com.launchpoint.wavdrop.ui.theme.EmeraldGreenPrimary
import com.launchpoint.wavdrop.ui.theme.OceanBluePrimary
import com.launchpoint.wavdrop.ui.theme.PrimaryViolet
import com.launchpoint.wavdrop.ui.theme.RosePinkPrimary
import com.launchpoint.wavdrop.ui.theme.SlateGrayPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(
    onNavigateBack: () -> Unit,
    onHomeCustomizationClick: () -> Unit,
    onWrappedAppearanceClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val appIconChoice        by viewModel.appIconChoice.collectAsStateWithLifecycle()
    val themeMode            by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor          by viewModel.accentColor.collectAsStateWithLifecycle()
    val compactMode          by viewModel.compactMode.collectAsStateWithLifecycle()
    val artworkCornerStyle   by viewModel.artworkCornerStyle.collectAsStateWithLifecycle()
    val showSongThumbnails   by viewModel.showSongThumbnails.collectAsStateWithLifecycle()
    val showAlbumInSongRows  by viewModel.showAlbumInSongRows.collectAsStateWithLifecycle()
    val nowPlayingBackground by viewModel.nowPlayingBackground.collectAsStateWithLifecycle()
    val showQueueCount       by viewModel.showQueueCount.collectAsStateWithLifecycle()
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
            // ── Theme ──────────────────────────────────────────────────────────
            item { SectionHeader("Theme") }
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
                        name        = color.displayName,
                        selected    = accentColor == color,
                        onClick     = { viewModel.setAccentColor(color) },
                        swatchColor = color.previewColor(),
                    )
                }
            }
            item { SectionDivider() }

            // ── Song Lists ─────────────────────────────────────────────────────
            item { SectionHeader("Song Lists") }
            item {
                ToggleSettingsRow(
                    title           = "Compact rows",
                    subtitle        = "Use denser rows in song lists and queue views.",
                    checked         = compactMode,
                    onCheckedChange = viewModel::setCompactMode,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Show album artwork",
                    subtitle        = "Display a thumbnail next to each song in lists.",
                    checked         = showSongThumbnails,
                    onCheckedChange = viewModel::setShowSongThumbnails,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Show album name",
                    subtitle        = "Show the album name below the artist in song rows.",
                    checked         = showAlbumInSongRows,
                    onCheckedChange = viewModel::setShowAlbumInSongRows,
                )
            }
            item { SectionDivider() }

            // ── Artwork ────────────────────────────────────────────────────────
            item { SectionHeader("Artwork") }
            item {
                SettingsMessageRow(
                    message = "Controls how album artwork corners are shown throughout the app.",
                )
            }
            ArtworkCornerStyle.entries.forEach { style ->
                item {
                    ScanModeRow(
                        title    = style.displayName,
                        subtitle = style.description,
                        selected = artworkCornerStyle == style,
                        onClick  = { viewModel.setArtworkCornerStyle(style) },
                    )
                }
            }
            item { SectionDivider() }

            // ── Now Playing ────────────────────────────────────────────────────
            item { SectionHeader("Now Playing") }
            NowPlayingBackground.entries.forEach { bg ->
                item {
                    ScanModeRow(
                        title    = bg.displayName,
                        subtitle = bg.description,
                        selected = nowPlayingBackground == bg,
                        onClick  = { viewModel.setNowPlayingBackground(bg) },
                    )
                }
            }
            item {
                ToggleSettingsRow(
                    title           = "Show queue count",
                    subtitle        = "Show the number of upcoming tracks on the queue handle.",
                    checked         = showQueueCount,
                    onCheckedChange = viewModel::setShowQueueCount,
                )
            }
            item { SectionDivider() }

            // ── Home ───────────────────────────────────────────────────────────
            item { SectionHeader("Home Screen") }
            item {
                ClickableSettingsRow(
                    title    = "Home Sections",
                    subtitle = "Choose which sections appear on your Home screen.",
                    onClick  = onHomeCustomizationClick,
                )
            }
            item { SectionDivider() }

            // ── Wrapped ────────────────────────────────────────────────────────
            item { SectionHeader("Wrapped") }
            item {
                ClickableSettingsRow(
                    title    = "Wrapped Appearance",
                    subtitle = "Customize Wrapped backgrounds, milestones, and themes.",
                    onClick  = onWrappedAppearanceClick,
                )
            }
            item { SectionDivider() }

            // ── App Icon ───────────────────────────────────────────────────────
            item { SectionHeader("App Icon") }
            item {
                SettingsMessageRow(
                    message = "Your launcher may take a moment to update the icon after you switch.",
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

private fun AccentColor.previewColor(): Color = when (this) {
    AccentColor.MIDNIGHT_VIOLET -> PrimaryViolet
    AccentColor.CLEAN_PURPLE    -> CleanPurplePrimary
    AccentColor.DEEP_TEAL       -> DeepTealPrimary
    AccentColor.OCEAN_BLUE      -> OceanBluePrimary
    AccentColor.EMERALD_GREEN   -> EmeraldGreenPrimary
    AccentColor.AMBER_GOLD      -> AmberGoldPrimary
    AccentColor.CRIMSON_RED     -> CrimsonRedPrimary
    AccentColor.ROSE_PINK       -> RosePinkPrimary
    AccentColor.SLATE_GRAY      -> SlateGrayPrimary
}
