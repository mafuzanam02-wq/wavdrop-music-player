package com.launchpoint.wavdrop.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.ui.screen.albums.AlbumDetailsScreen
import com.launchpoint.wavdrop.ui.components.ChangelogDialog
import com.launchpoint.wavdrop.ui.components.LocalArtworkCornerStyle
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.LocalNowPlayingBackground
import com.launchpoint.wavdrop.ui.components.LocalShowAlbumInSongRows
import com.launchpoint.wavdrop.ui.components.LocalNowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.ui.components.LocalShowQueueCount
import com.launchpoint.wavdrop.ui.components.LocalShowSongThumbnails
import com.launchpoint.wavdrop.ui.screen.albums.AlbumsScreen
import com.launchpoint.wavdrop.ui.screen.artists.ArtistDetailsScreen
import com.launchpoint.wavdrop.ui.screen.artists.ArtistsScreen
import com.launchpoint.wavdrop.ui.screen.backupimport.BackupImportPreviewScreen
import com.launchpoint.wavdrop.ui.screen.bpstatpreview.BpstatPreviewScreen
import com.launchpoint.wavdrop.ui.screen.folders.FolderDetailsScreen
import com.launchpoint.wavdrop.ui.screen.folders.FoldersScreen
import com.launchpoint.wavdrop.ui.screen.home.HomeCustomizationScreen
import com.launchpoint.wavdrop.ui.screen.home.HomeScreen
import com.launchpoint.wavdrop.ui.screen.library.LibraryScreen
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingScreen
import com.launchpoint.wavdrop.ui.screen.onboarding.OnboardingScreen
import com.launchpoint.wavdrop.ui.screen.playlists.AddSongsToPlaylistScreen
import com.launchpoint.wavdrop.ui.screen.playlists.PlaylistDetailsScreen
import com.launchpoint.wavdrop.ui.screen.playlists.PlaylistsScreen
import com.launchpoint.wavdrop.ui.screen.monthlyreports.MonthlyReportsScreen
import com.launchpoint.wavdrop.ui.screen.reports.ReportsScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsAboutScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsAppearanceScreen
import com.launchpoint.wavdrop.ui.screen.settings.BackupVerificationScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsBackupScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsBluetoothScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsDiagnosticsScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsLibraryScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsPlaybackScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsScreen
import com.launchpoint.wavdrop.ui.screen.settings.SettingsStatisticsScreen
import com.launchpoint.wavdrop.ui.screen.smart.SmartCollectionDetailsScreen
import com.launchpoint.wavdrop.ui.screen.smart.SmartCollectionsScreen
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.ui.screen.songs.SongsScreen
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsScreen
import com.launchpoint.wavdrop.ui.screen.trackdetails.TrackDetailsScreen
import com.launchpoint.wavdrop.ui.screen.search.GlobalSearchRoute
import com.launchpoint.wavdrop.ui.screen.wrapped.WrappedScreen

sealed class Screen(val route: String) {
    data object Onboarding          : Screen("onboarding")
    data object Home                : Screen("home")
    data object Library             : Screen("library")
    data object Songs               : Screen("songs")
    data object BpstatPreview       : Screen("bpstat_preview")
    data object BackupImportPreview : Screen("backup_import_preview")
    data object NowPlaying          : Screen("now_playing")
    data object Settings            : Screen("settings")
    data object SettingsPlayback    : Screen("settings/playback")
    data object SettingsBluetooth   : Screen("settings/bluetooth")
    data object SettingsLibrary     : Screen("settings/library")
    data object SettingsBackup      : Screen("settings/backup")
    data object SettingsBackupVerification : Screen("settings/backup/verification")
    data object SettingsAppearance  : Screen("settings/appearance")
    data object SettingsStatistics  : Screen("settings/statistics")
    data object SettingsAbout       : Screen("settings/about")
    data object SettingsDiagnostics : Screen("settings/about/diagnostics")
    data object HomeCustomization   : Screen("home_customization")
    data object Albums              : Screen("albums")
    data object Artists             : Screen("artists")
    data object Folders             : Screen("folders")
    data object Statistics          : Screen("statistics")
    data object Reports             : Screen("reports")
    data object MonthlyReports      : Screen("monthly_reports")
    data object GlobalSearch        : Screen("global_search")
    data object Wrapped             : Screen("wrapped")
    data object Playlists              : Screen("playlists")
    data object SmartCollections      : Screen("smart_collections")
    object TrackDetails               : Screen("track_details/{songId}") {
        fun createRoute(songId: Long) = "track_details/$songId"
    }
    object PlaylistDetails            : Screen("playlist_details/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_details/$playlistId"
    }
    object AddSongsToPlaylist         : Screen("playlist_details/{playlistId}/add_songs") {
        fun createRoute(playlistId: Long) = "playlist_details/$playlistId/add_songs"
    }
    object SmartCollectionDetails     : Screen("smart_collection_details/{type}") {
        fun createRoute(type: SmartCollectionType) = "smart_collection_details/${type.name}"
    }
    object AlbumDetails               : Screen("album_details/{albumKey}") {
        fun createRoute(albumKey: String) = "album_details/${Uri.encode(albumKey)}"
    }
    object ArtistDetails            : Screen("artist_details/{artistKey}") {
        fun createRoute(artistKey: String) = "artist_details/${Uri.encode(artistKey)}"
    }
    object FolderDetails            : Screen("folder_details/{folderKey}") {
        fun createRoute(folderKey: String) = "folder_details/${Uri.encode(folderKey)}"
    }
}

private fun NavHostController.navigatePrimary(route: String) {
    if (route == Screen.Home.route) {
        // popBackStack avoids the saveState/restoreState cycle that would re-add
        // nested screens (e.g. NowPlaying) on top of Home. If Home is not in the
        // back stack (startup != Home), navigate to it fresh.
        if (!popBackStack(route, inclusive = false)) {
            navigate(route) { launchSingleTop = true }
        }
    } else {
        navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(Screen.Home.route) {
                saveState = true
            }
        }
    }
}

private fun NavHostController.navigateNowPlaying() {
    navigate(Screen.NowPlaying.route) {
        launchSingleTop = true
    }
}

@Composable
fun WavdropNavGraph(
    navController: NavHostController = rememberNavController(),
    viewModel: WavdropNavViewModel = hiltViewModel(),
) {
    val startupDestination by viewModel.startupDestination.collectAsStateWithLifecycle()
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsStateWithLifecycle()
    val compactMode          by viewModel.compactMode.collectAsStateWithLifecycle()
    val showSongThumbnails   by viewModel.showSongThumbnails.collectAsStateWithLifecycle()
    val showAlbumInSongRows  by viewModel.showAlbumInSongRows.collectAsStateWithLifecycle()
    val artworkCornerStyle   by viewModel.artworkCornerStyle.collectAsStateWithLifecycle()
    val nowPlayingBackground by viewModel.nowPlayingBackground.collectAsStateWithLifecycle()
    val showQueueCount            by viewModel.showQueueCount.collectAsStateWithLifecycle()
    val nowPlayingTimeDisplayMode by viewModel.nowPlayingTimeDisplayMode.collectAsStateWithLifecycle()
    val showChangelog        by viewModel.showChangelog.collectAsStateWithLifecycle()
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(startupDestination, hasCompletedOnboarding) {
        if (startRoute == null && startupDestination != null && hasCompletedOnboarding != null) {
            startRoute = if (hasCompletedOnboarding == true) {
                startupDestination!!.toRoute()
            } else {
                Screen.Onboarding.route
            }
        }
    }

    val resolvedStartRoute = startRoute
    if (resolvedStartRoute == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    CompositionLocalProvider(
        LocalCompactMode         provides compactMode,
        LocalShowSongThumbnails  provides showSongThumbnails,
        LocalShowAlbumInSongRows provides showAlbumInSongRows,
        LocalArtworkCornerStyle  provides artworkCornerStyle,
        LocalNowPlayingBackground provides nowPlayingBackground,
        LocalShowQueueCount           provides showQueueCount,
        LocalNowPlayingTimeDisplayMode provides nowPlayingTimeDisplayMode,
    ) {
        // Fire auto-backup check once per session, after onboarding is confirmed complete.
        LaunchedEffect(Unit) {
            if (hasCompletedOnboarding == true) {
                viewModel.triggerAutoBackupIfDue()
            }
        }

        NavHost(
            navController    = navController,
            startDestination = resolvedStartRoute,
        ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    viewModel.completeOnboarding()
                    navController.navigate((startupDestination ?: StartupDestination.SONGS).toRoute()) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onSettingsClick     = { navController.navigate(Screen.Settings.route) },
                onInsightsClick     = { navController.navigatePrimary(Screen.SettingsStatistics.route) },
                onSongsClick        = { navController.navigatePrimary(Screen.Songs.route) },
                onLibraryClick      = { navController.navigatePrimary(Screen.Library.route) },
                onNowPlayingClick   = { navController.navigateNowPlaying() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onFolderClick       = { folderKey -> navController.navigate(Screen.FolderDetails.createRoute(folderKey)) },
                onPlaylistsClick         = { navController.navigate(Screen.Playlists.route) },
                onSmartCollectionsClick  = { navController.navigate(Screen.SmartCollections.route) },
                onWrappedClick           = { navController.navigate(Screen.Wrapped.route) },
                onAlbumClick             = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
                onArtistClick            = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onGlobalSearchClick      = { navController.navigate(Screen.GlobalSearch.route) },
                onReportsAndInsightsClick = { navController.navigate(Screen.SettingsStatistics.route) },
            )
        }
        composable(Screen.Library.route) {
            LibraryScreen(
                onSongsClick            = { navController.navigate(Screen.Songs.route) },
                onAlbumsClick           = { navController.navigate(Screen.Albums.route) },
                onArtistsClick          = { navController.navigate(Screen.Artists.route) },
                onFoldersClick          = { navController.navigate(Screen.Folders.route) },
                onPlaylistsClick        = { navController.navigate(Screen.Playlists.route) },
                onSmartCollectionsClick = { navController.navigate(Screen.SmartCollections.route) },
                onHomeClick             = { navController.navigatePrimary(Screen.Home.route) },
                onNowPlayingClick       = { navController.navigateNowPlaying() },
                onInsightsClick         = { navController.navigatePrimary(Screen.SettingsStatistics.route) },
            )
        }
        composable(Screen.Songs.route) {
            SongsScreen(
                onNavigateBack         = { navController.popBackStack() },
                onHomeClick            = { navController.navigatePrimary(Screen.Home.route) },
                onLibraryClick         = { navController.navigatePrimary(Screen.Library.route) },
                onNowPlayingClick      = { navController.navigateNowPlaying() },
                onInsightsClick        = { navController.navigatePrimary(Screen.SettingsStatistics.route) },
                onLibrarySettingsClick = { navController.navigate(Screen.SettingsLibrary.route) },
                onTrackDetailsClick    = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onFolderClick       = { folderKey -> navController.navigate(Screen.FolderDetails.createRoute(folderKey)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
            )
        }
        composable(Screen.BpstatPreview.route) {
            BpstatPreviewScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Screen.BackupImportPreview.route}?uri={uri}",
            arguments = listOf(
                navArgument("uri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val selectedUri = backStackEntry.arguments
                ?.getString("uri")
                ?.let(Uri::parse)
            BackupImportPreviewScreen(
                selectedUri = selectedUri,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Screen.NowPlaying.route) {
            NowPlayingScreen(
                onNavigateBack      = { navController.popBackStack() },
                onHomeClick         = { navController.navigatePrimary(Screen.Home.route) },
                onSongsClick        = { navController.navigatePrimary(Screen.Songs.route) },
                onLibraryClick      = { navController.navigatePrimary(Screen.Library.route) },
                onInsightsClick     = { navController.navigatePrimary(Screen.SettingsStatistics.route) },
                onOpenTrackDetails  = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onOpenAlbum         = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
                onOpenArtist        = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onOpenFolder        = { folderKey -> navController.navigate(Screen.FolderDetails.createRoute(folderKey)) },
                onOpenStatistics    = { navController.navigate(Screen.Statistics.route) },
            )
        }
        composable(
            route = Screen.TrackDetails.route,
            arguments = listOf(
                navArgument("songId") { type = NavType.LongType },
            ),
        ) {
            TrackDetailsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Albums.route) {
            AlbumsScreen(
                onNavigateBack = { navController.popBackStack() },
                onAlbumClick   = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
            )
        }
        composable(
            route     = Screen.AlbumDetails.route,
            arguments = listOf(
                navArgument("albumKey") { type = NavType.StringType },
            ),
        ) {
            AlbumDetailsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
            )
        }
        composable(Screen.Artists.route) {
            ArtistsScreen(
                onNavigateBack = { navController.popBackStack() },
                onArtistClick  = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
            )
        }
        composable(
            route     = Screen.ArtistDetails.route,
            arguments = listOf(
                navArgument("artistKey") { type = NavType.StringType },
            ),
        ) {
            ArtistDetailsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
            )
        }
        composable(Screen.Folders.route) {
            FoldersScreen(
                onNavigateBack    = { navController.popBackStack() },
                onFolderClick     = { folderKey -> navController.navigate(Screen.FolderDetails.createRoute(folderKey)) },
                onNowPlayingClick = { navController.navigateNowPlaying() },
            )
        }
        composable(Screen.Statistics.route) {
            StatisticsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
            )
        }
        composable(Screen.Reports.route) {
            ReportsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
            )
        }
        composable(Screen.MonthlyReports.route) {
            MonthlyReportsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
            )
        }
        composable(Screen.Wrapped.route) {
            WrappedScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
                onNavigateToSongs   = { navController.navigatePrimary(Screen.Songs.route) },
            )
        }
        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                onNavigateBack    = { navController.popBackStack() },
                onPlaylistClick   = { playlistId -> navController.navigate(Screen.PlaylistDetails.createRoute(playlistId)) },
                onNowPlayingClick = { navController.navigateNowPlaying() },
            )
        }
        composable(Screen.SmartCollections.route) {
            SmartCollectionsScreen(
                onNavigateBack    = { navController.popBackStack() },
                onCollectionClick = { type -> navController.navigate(Screen.SmartCollectionDetails.createRoute(type)) },
                onNowPlayingClick = { navController.navigateNowPlaying() },
            )
        }
        composable(
            route     = Screen.SmartCollectionDetails.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
            ),
        ) {
            SmartCollectionDetailsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onNowPlayingClick   = { navController.navigateNowPlaying() },
            )
        }
        composable(
            route     = Screen.PlaylistDetails.route,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val playlistId = checkNotNull(backStackEntry.arguments?.getLong("playlistId"))
            val addSongsResult by backStackEntry.savedStateHandle
                .getStateFlow<String?>("add_songs_result", null)
                .collectAsStateWithLifecycle()
            PlaylistDetailsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onAddSongsClick     = { navController.navigate(Screen.AddSongsToPlaylist.createRoute(playlistId)) },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onNowPlayingClick   = { navController.navigateNowPlaying() },
                pendingMessage      = addSongsResult,
                onMessageConsumed   = { backStackEntry.savedStateHandle.remove<String>("add_songs_result") },
            )
        }
        composable(
            route     = Screen.AddSongsToPlaylist.route,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
            ),
        ) {
            AddSongsToPlaylistScreen(
                onNavigateBack = { navController.popBackStack() },
                onAddComplete  = { message ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("add_songs_result", message)
                    navController.popBackStack()
                },
            )
        }
        composable(
            route     = Screen.FolderDetails.route,
            arguments = listOf(
                navArgument("folderKey") { type = NavType.StringType },
            ),
        ) {
            FolderDetailsScreen(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onNowPlayingClick   = { navController.navigateNowPlaying() },
            )
        }
        composable(Screen.GlobalSearch.route) {
            GlobalSearchRoute(
                onNavigateBack      = { navController.popBackStack() },
                onTrackDetailsClick = { songId -> navController.navigate(Screen.TrackDetails.createRoute(songId)) },
                onAlbumClick        = { albumKey -> navController.navigate(Screen.AlbumDetails.createRoute(albumKey)) },
                onArtistClick       = { artistKey -> navController.navigate(Screen.ArtistDetails.createRoute(artistKey)) },
                onFolderClick       = { folderKey -> navController.navigate(Screen.FolderDetails.createRoute(folderKey)) },
                onPlaylistClick     = { playlistId -> navController.navigate(Screen.PlaylistDetails.createRoute(playlistId)) },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack         = { navController.popBackStack() },
                onPlaybackClick        = { navController.navigate(Screen.SettingsPlayback.route) },
                onBluetoothClick       = { navController.navigate(Screen.SettingsBluetooth.route) },
                onLibrarySettingsClick = { navController.navigate(Screen.SettingsLibrary.route) },
                onBackupClick          = { navController.navigate(Screen.SettingsBackup.route) },
                onAppearanceClick      = { navController.navigate(Screen.SettingsAppearance.route) },
                onStatisticsClick      = { navController.navigate(Screen.SettingsStatistics.route) },
                onAboutClick           = { navController.navigate(Screen.SettingsAbout.route) },
            )
        }
        composable(Screen.SettingsPlayback.route) {
            SettingsPlaybackScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.SettingsBluetooth.route) {
            SettingsBluetoothScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.SettingsLibrary.route) {
            SettingsLibraryScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.SettingsBackup.route) {
            SettingsBackupScreen(
                onNavigateBack      = { navController.popBackStack() },
                onImportClick       = { navController.navigate(Screen.BpstatPreview.route) },
                onBackupImportClick = { uri ->
                    navController.navigate(
                        "${Screen.BackupImportPreview.route}?uri=${Uri.encode(uri.toString())}"
                    )
                },
                onVerificationClick = {
                    navController.navigate(Screen.SettingsBackupVerification.route)
                },
            )
        }
        composable(Screen.SettingsBackupVerification.route) {
            BackupVerificationScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.SettingsAppearance.route) {
            SettingsAppearanceScreen(
                onNavigateBack           = { navController.popBackStack() },
                onHomeCustomizationClick = { navController.navigate(Screen.HomeCustomization.route) },
            )
        }
        composable(Screen.SettingsStatistics.route) {
            val previousRoute = navController.previousBackStackEntry?.destination?.route
            val fromSettings = previousRoute == Screen.Settings.route
            SettingsStatisticsScreen(
                onNavigateBack        = { navController.popBackStack() },
                onStatisticsClick     = { navController.navigate(Screen.Statistics.route) },
                onReportsClick        = { navController.navigate(Screen.Reports.route) },
                onMonthlyReportsClick = { navController.navigate(Screen.MonthlyReports.route) },
                onWrappedClick        = { navController.navigate(Screen.Wrapped.route) },
                showBackArrow         = fromSettings,
                onHomeClick           = { navController.navigatePrimary(Screen.Home.route) },
                onSongsClick          = { navController.navigatePrimary(Screen.Songs.route) },
                onLibraryClick        = { navController.navigatePrimary(Screen.Library.route) },
                onInsightsClick       = {},
                onNowPlayingClick     = { navController.navigateNowPlaying() },
            )
        }
        composable(Screen.SettingsAbout.route) {
            SettingsAboutScreen(
                onNavigateBack = { navController.popBackStack() },
                onDiagnosticsClick = { navController.navigate(Screen.SettingsDiagnostics.route) },
            )
        }
        composable(Screen.SettingsDiagnostics.route) {
            SettingsDiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.HomeCustomization.route) {
            HomeCustomizationScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        }
        if (showChangelog) {
            ChangelogDialog(onDismiss = viewModel::dismissChangelog)
        }
    }
}

private fun StartupDestination.toRoute(): String = when (this) {
    StartupDestination.HOME -> Screen.Home.route
    StartupDestination.LIBRARY -> Screen.Library.route
    StartupDestination.SONGS -> Screen.Songs.route
    StartupDestination.NOW_PLAYING -> Screen.NowPlaying.route
    StartupDestination.SETTINGS -> Screen.Settings.route
}
