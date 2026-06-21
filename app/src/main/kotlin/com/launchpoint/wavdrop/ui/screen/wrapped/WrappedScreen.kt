package com.launchpoint.wavdrop.ui.screen.wrapped

import android.app.Activity
import android.graphics.Rect as AndroidRect
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Base page count: Intro + 8 existing pages (Overview, Streaks, Patterns, Top Track/Artist/Album,
// Most Skipped, Recent Plays). Milestone page appended conditionally.
private const val WRAPPED_BASE_COUNT = 9

internal data class WrappedPeriodCopy(
    val shortLabel: String,
    val displayLabel: String,
    val thisPeriod: String,
    val duringThisPeriod: String,
    val introSubtitle: String = "in Review",
) {
    val inReviewTitle: String get() = "$displayLabel in Review"
}

internal fun WrappedPeriod.toWrappedPeriodCopy(): WrappedPeriodCopy = when (this) {
    is WrappedPeriod.Monthly -> WrappedPeriodCopy(
        shortLabel = shortLabel,
        displayLabel = displayLabel,
        thisPeriod = "this month",
        duringThisPeriod = "during this month",
    )
    is WrappedPeriod.Yearly -> WrappedPeriodCopy(
        shortLabel = shortLabel,
        displayLabel = displayLabel,
        thisPeriod = "this year",
        duringThisPeriod = "during this year",
    )
    is WrappedPeriod.AllTime -> WrappedPeriodCopy(
        shortLabel = "All Time",
        displayLabel = "All Time",
        thisPeriod = "in your history",
        duringThisPeriod = "in your history",
        introSubtitle = "Your Story",
    )
}

internal fun shouldShowWrappedMilestonePage(
    scope: WrappedScope,
    preferenceEnabled: Boolean,
    hasMilestones: Boolean,
): Boolean =
    scope == WrappedScope.YEARLY && preferenceEnabled && hasMilestones

private const val WRAPPED_ALL_TIME_PAGE_COUNT = 7

internal fun <T> wrappedTopThree(items: List<T>): List<T> = items.take(3)

internal enum class WrappedRankedKind(
    val singular: String,
    val plural: String,
    val emptySubject: String,
) {
    TRACK("track", "tracks", "played tracks"),
    ARTIST("artist", "artists", "artists"),
    ALBUM("album", "albums", "albums"),
}

internal fun wrappedRankedListNote(
    kind: WrappedRankedKind,
    itemCount: Int,
    periodCopy: WrappedPeriodCopy,
): String? = when (itemCount) {
    0 -> "No ${kind.emptySubject} ranked ${periodCopy.thisPeriod} yet."
    1 -> "Only one ${kind.singular} ranked ${periodCopy.thisPeriod}."
    2 -> "Only two ${kind.plural} ranked ${periodCopy.thisPeriod}."
    else -> null
}

internal fun wrappedPeriodSelectorHelperText(availableCount: Int, kind: String): String? =
    if (availableCount <= 1) "More $kind will appear here as you keep listening in Wavdrop." else null

internal fun wrappedSkipRatePercent(totalPlays: Int, totalSkips: Int): Int {
    val activityCount = totalPlays.coerceAtLeast(0) + totalSkips.coerceAtLeast(0)
    if (activityCount == 0) return 0
    return ((totalSkips.coerceAtLeast(0).toDouble() / activityCount) * 100.0)
        .roundToInt()
}

internal fun wrappedPageCount(
    scope: WrappedScope,
    milestonePreferenceEnabled: Boolean,
    hasMilestones: Boolean,
): Int = when (scope) {
    WrappedScope.ALL_TIME -> WRAPPED_ALL_TIME_PAGE_COUNT
    else -> WRAPPED_BASE_COUNT + if (
        shouldShowWrappedMilestonePage(
            scope = scope,
            preferenceEnabled = milestonePreferenceEnabled,
            hasMilestones = hasMilestones,
        )
    ) 1 else 0
}

// Intro gets 4 s; ranked list slides (Top Tracks/Artists/Albums) get 6 s to allow reading;
// all other slides get the default 5 s.
internal fun wrappedSlideDurationMs(
    page: Int,
    scope: WrappedScope,
    showMilestonePage: Boolean,
): Int = when (scope) {
    WrappedScope.ALL_TIME -> when (page) {
        0       -> 4_000  // Intro
        2, 3, 4 -> 7_000  // Top Tracks, Top Artists, Top Albums
        else    -> 5_000  // Overview, Skip Habits, Recent Plays
    }
    else -> when (page) {
        0       -> 4_000  // Intro
        4, 5, 6 -> 7_000  // Top Tracks, Top Artists, Top Albums
        else    -> 5_000  // Overview, Streaks, Patterns, Skip Habits, Recent Plays, Milestones
    }
}

internal fun wrappedDataSourceDisclosure(scope: WrappedScope): String = when (scope) {
    WrappedScope.ALL_TIME ->
        "All Time is based on your aggregate listening totals on this device, including restored or imported stats."
    WrappedScope.MONTHLY,
    WrappedScope.YEARLY ->
        "Wrapped is based on listening activity recorded by Wavdrop on this device. Imported totals may appear in Statistics, but they are not included here."
}

private data class WrappedVisualSettings(
    val useArtworkBackgrounds: Boolean,
    val backgroundIntensity: WrappedBackgroundIntensity,
    val fallbackTheme: WrappedFallbackTheme,
)

private data class WrappedIntensityStyle(
    val artworkTopScrimAlpha: Float,
    val artworkBottomScrimAlpha: Float,
    val decorativeContainerAlpha: Float,
    val decorativeMotifMultiplier: Float,
)

private fun WrappedBackgroundIntensity.toVisualStyle(): WrappedIntensityStyle = when (this) {
    WrappedBackgroundIntensity.SUBTLE -> WrappedIntensityStyle(
        artworkTopScrimAlpha = 0.38f,
        artworkBottomScrimAlpha = 0.78f,
        decorativeContainerAlpha = 0.055f,
        decorativeMotifMultiplier = 0.65f,
    )
    WrappedBackgroundIntensity.MEDIUM -> WrappedIntensityStyle(
        artworkTopScrimAlpha = 0.28f,
        artworkBottomScrimAlpha = 0.68f,
        decorativeContainerAlpha = 0.08f,
        decorativeMotifMultiplier = 1f,
    )
    WrappedBackgroundIntensity.BOLD -> WrappedIntensityStyle(
        artworkTopScrimAlpha = 0.22f,
        artworkBottomScrimAlpha = 0.62f,
        decorativeContainerAlpha = 0.13f,
        decorativeMotifMultiplier = 1.45f,
    )
}

private data class WrappedFallbackPalette(
    val containerColor: Color,
    val primary: Color,
    val secondary: Color,
)

@Composable
private fun WrappedFallbackTheme.toPalette(
    motif: WrappedDecorativeMotif,
    intensityStyle: WrappedIntensityStyle,
): WrappedFallbackPalette {
    val fallbackColor = when (this) {
        WrappedFallbackTheme.AUTO -> when (motif) {
            WrappedDecorativeMotif.Milestones -> MaterialTheme.colorScheme.primary
            WrappedDecorativeMotif.Patterns -> MaterialTheme.colorScheme.secondary
        }
        WrappedFallbackTheme.OBSIDIAN -> Color(0xFF3A3A42)
        WrappedFallbackTheme.OCEAN -> Color(0xFF0B6F92)
        WrappedFallbackTheme.DEEP_TEAL -> Color(0xFF08746A)
        WrappedFallbackTheme.MIDNIGHT_VIOLET -> Color(0xFF6D4DBA)
        WrappedFallbackTheme.SUNSET_ORANGE -> Color(0xFFC66B2E)
        WrappedFallbackTheme.CLEAN_PURPLE -> Color(0xFF6D5BD0)
    }
    val secondary = when (this) {
        WrappedFallbackTheme.AUTO -> MaterialTheme.colorScheme.secondary
        WrappedFallbackTheme.OBSIDIAN -> Color(0xFF8F8F99)
        WrappedFallbackTheme.OCEAN -> Color(0xFF14B8D4)
        WrappedFallbackTheme.DEEP_TEAL -> Color(0xFF2CC8A7)
        WrappedFallbackTheme.MIDNIGHT_VIOLET -> Color(0xFFB08CFF)
        WrappedFallbackTheme.SUNSET_ORANGE -> Color(0xFFE9A85B)
        WrappedFallbackTheme.CLEAN_PURPLE -> Color(0xFF8EA1FF)
    }
    return WrappedFallbackPalette(
        containerColor = fallbackColor.copy(alpha = intensityStyle.decorativeContainerAlpha),
        primary = fallbackColor,
        secondary = secondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrappedScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onNavigateToSongs: () -> Unit = {},
    onWrappedAppearanceClick: () -> Unit = {},
    viewModel: WrappedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wrapped") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onWrappedAppearanceClick) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Wrapped appearance settings",
                            tint               = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            WrappedUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            WrappedUiState.Empty -> EmptyContent(
                onNavigateToSongs = onNavigateToSongs,
                modifier = Modifier.padding(innerPadding),
            )
            is WrappedUiState.Content -> WrappedContent(
                state = state,
                onSelectScope = viewModel::selectScope,
                onSelectYear = viewModel::selectYear,
                onSelectMonth = viewModel::selectMonth,
                onTrackDetailsClick = onTrackDetailsClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    LoadingStateContent(message = "Loading Wrapped...", modifier = modifier)
}

@Composable
private fun EmptyContent(
    onNavigateToSongs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Your Wrapped starts here.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Play music in Wavdrop to build private monthly and yearly recaps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNavigateToSongs) {
                Text("Start listening")
            }
        }
    }
}

@Composable
private fun WrappedContent(
    state: WrappedUiState.Content,
    onSelectScope: (WrappedScope) -> Unit,
    onSelectYear: (Int) -> Unit,
    onSelectMonth: (MonthYear) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) < 0.1f
    }

    val wrapped = state.summary
    val periodCopy = state.currentPeriod.toWrappedPeriodCopy()
    val visualSettings = WrappedVisualSettings(
        useArtworkBackgrounds = state.useArtworkBackgrounds,
        backgroundIntensity = state.backgroundIntensity,
        fallbackTheme = state.fallbackTheme,
    )

    val isYearly = state.selectedScope == WrappedScope.YEARLY
    val milestones = remember(wrapped, isYearly) {
        if (isYearly) computeMilestones(wrapped) else emptyList<WrappedMilestone>()
    }
    val showMilestonePage = shouldShowWrappedMilestonePage(
        scope = state.selectedScope,
        preferenceEnabled = state.showMilestoneCelebrations,
        hasMilestones = milestones.isNotEmpty(),
    )
    val pageCount = wrappedPageCount(
        scope = state.selectedScope,
        milestonePreferenceEnabled = state.showMilestoneCelebrations,
        hasMilestones = milestones.isNotEmpty(),
    )

    val pagerState = rememberPagerState(pageCount = { pageCount })
    var pagerBoundsInWindow by remember { mutableStateOf<AndroidRect?>(null) }

    // ── Story mode ────────────────────────────────────────────────────────────
    // Story starts playing automatically unless the OS reduce-motion flag is set.
    var storyPlaying by remember { mutableStateOf(!reduceMotion) }
    val slideProgress = remember { Animatable(0f) }

    LaunchedEffect(state.currentPeriod) {
        pagerState.scrollToPage(0)
        slideProgress.snapTo(0f)
        storyPlaying = !reduceMotion
    }

    LaunchedEffect(pageCount) {
        if (pageCount > 0 && pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage(pageCount - 1)
        }
    }

    val currentStoryPage = pagerState.settledPage

    // Resets progress when the page changes (manual swipe, auto-advance, scope/period change).
    // Pause/resume does not change settledPage, so this effect does not run on toggle — progress
    // is preserved across pause/resume cycles by the Animatable cancellation mechanism.
    LaunchedEffect(currentStoryPage) {
        slideProgress.snapTo(0f)
    }

    // Drives the progress bar and auto-advance. Restarts on page change (progress already
    // reset above) or on play/pause toggle (progress preserved from cancellation point).
    // On resume, remaining duration is proportional to the remaining progress fraction so the
    // bar always reaches 1f at the same per-slide rate regardless of where it was paused.
    LaunchedEffect(currentStoryPage, storyPlaying) {
        if (!storyPlaying || reduceMotion) return@LaunchedEffect
        val isLastPage = currentStoryPage >= pageCount - 1
        val durationMs = wrappedSlideDurationMs(currentStoryPage, state.selectedScope, showMilestonePage)
        val remainingFraction = (1f - slideProgress.value).coerceIn(0f, 1f)
        val remainingMs = (durationMs * remainingFraction).roundToInt().coerceAtLeast(1)
        slideProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = remainingMs, easing = LinearEasing),
        )
        if (isLastPage) {
            storyPlaying = false
        } else {
            pagerState.animateScrollToPage(currentStoryPage + 1)
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    Column(modifier = modifier.fillMaxSize()) {
        WrappedScopeSelector(
            selectedScope = state.selectedScope,
            onSelectScope = onSelectScope,
        )
        when (state.selectedScope) {
            WrappedScope.MONTHLY -> WrappedMonthSelector(
                month = state.selectedMonth,
                availableMonths = state.availableMonths,
                accessibilityLabel = state.currentPeriod.accessibilityLabel,
                onSelectMonth = onSelectMonth,
            )
            WrappedScope.YEARLY -> WrappedYearSelector(
                year = state.selectedYear,
                availableYears = state.availableYears,
                accessibilityLabel = state.currentPeriod.accessibilityLabel,
                onSelectYear = onSelectYear,
            )
            WrappedScope.ALL_TIME -> Unit
        }

        Text(
            text = wrappedDataSourceDisclosure(state.selectedScope),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (wrapped.emptyState.isEmpty) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val emptyTitle = if (state.selectedScope == WrappedScope.ALL_TIME) {
                        "Your listening story starts here."
                    } else {
                        "No listening summary for this period"
                    }
                    val emptyBody = if (state.selectedScope == WrappedScope.ALL_TIME) {
                        "Play some music to see your listening story here."
                    } else {
                        "Try another month or year with listening activity."
                    }
                    Text(
                        text = emptyTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = emptyBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val b = coordinates.boundsInWindow()
                        pagerBoundsInWindow = AndroidRect(
                            b.left.toInt(),
                            b.top.toInt(),
                            b.right.toInt(),
                            b.bottom.toInt(),
                        )
                    },
            ) { page ->
                val isCurrentPage = pagerState.settledPage == page
                val tapToggleModifier = if (!reduceMotion) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { storyPlaying = !storyPlaying }
                    }
                } else Modifier
                Box(modifier = Modifier.fillMaxSize().then(tapToggleModifier)) {
                when (state.selectedScope) {
                    WrappedScope.ALL_TIME -> when (page) {
                        0    -> IntroPage(wrapped, periodCopy, visualSettings)
                        1    -> OverviewPage(wrapped, periodCopy, isCurrentPage, reduceMotion)
                        2    -> TopTracksPage(wrapped, periodCopy, visualSettings, onTrackDetailsClick)
                        3    -> TopArtistsPage(wrapped, periodCopy, visualSettings, onArtistClick)
                        4    -> TopAlbumsPage(wrapped, periodCopy, visualSettings, onAlbumClick)
                        5    -> SkipHabitsPage(wrapped, periodCopy, visualSettings, onTrackDetailsClick, isCurrentPage, reduceMotion)
                        else -> RecentPlaysPage(wrapped, periodCopy, onTrackDetailsClick)
                    }
                    else -> when (page) {
                        0    -> IntroPage(wrapped, periodCopy, visualSettings)
                        1    -> OverviewPage(wrapped, periodCopy, isCurrentPage, reduceMotion)
                        2    -> StreaksPage(wrapped, periodCopy, isCurrentPage, reduceMotion)
                        3    -> PatternsPage(wrapped, periodCopy, visualSettings)
                        4    -> TopTracksPage(wrapped, periodCopy, visualSettings, onTrackDetailsClick)
                        5    -> TopArtistsPage(wrapped, periodCopy, visualSettings, onArtistClick)
                        6    -> TopAlbumsPage(wrapped, periodCopy, visualSettings, onAlbumClick)
                        7    -> SkipHabitsPage(wrapped, periodCopy, visualSettings, onTrackDetailsClick, isCurrentPage, reduceMotion)
                        8    -> RecentPlaysPage(wrapped, periodCopy, onTrackDetailsClick)
                        else -> if (showMilestonePage) {
                            MilestonePage(
                                milestones = milestones,
                                periodLabel = state.currentPeriod.displayLabel,
                                visualSettings = visualSettings,
                                reduceMotion = reduceMotion,
                            )
                        } else {
                            RecentPlaysPage(wrapped, periodCopy, onTrackDetailsClick)
                        }
                    }
                }
                } // Box(tapToggleModifier)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (!reduceMotion) {
                    LinearProgressIndicator(
                        progress = { slideProgress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    if (!reduceMotion) {
                        IconButton(
                            onClick = { storyPlaying = !storyPlaying },
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(
                                imageVector = if (storyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (storyPlaying) "Pause story" else "Play story",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    PageIndicator(
                        pageCount = pageCount,
                        currentPage = pagerState.settledPage,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    IconButton(
                        onClick = {
                            val rect = pagerBoundsInWindow ?: return@IconButton
                            val act = activity ?: return@IconButton
                            storyPlaying = false
                            shareWrappedSlide(act, rect)
                        },
                        enabled = !pagerState.isScrollInProgress && pagerBoundsInWindow != null && activity != null,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share this slide",
                        )
                    }
                }
            }
        }
    }
}

// ── Year selector ─────────────────────────────────────────────────────────────

@Composable
private fun WrappedScopeSelector(
    selectedScope: WrappedScope,
    onSelectScope: (WrappedScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        WrappedScope.entries.forEachIndexed { index, scope ->
            SegmentedButton(
                selected = selectedScope == scope,
                onClick = { onSelectScope(scope) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = WrappedScope.entries.size,
                ),
                label = { Text(scope.displayName) },
            )
        }
    }
}

@Composable
private fun WrappedYearSelector(
    year: Int,
    availableYears: List<Int>,
    accessibilityLabel: String,
    onSelectYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSelectYear = availableYears.size > 1
    val selectedIndex = availableYears.indexOf(year)
    val hasPrevious = selectedIndex >= 0 && selectedIndex < availableYears.lastIndex
    val hasNext = selectedIndex > 0
    val helperText = wrappedPeriodSelectorHelperText(availableYears.size, "years")

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = { onSelectYear(availableYears[selectedIndex + 1]) },
            enabled = hasPrevious,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous year",
                tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = canSelectYear,
                modifier = Modifier.semantics {
                    contentDescription = accessibilityLabel
                },
            ) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (canSelectYear) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    },
                )
            }
            DropdownMenu(
                expanded = expanded && canSelectYear,
                onDismissRequest = { expanded = false },
            ) {
                availableYears.forEach { availableYear ->
                    DropdownMenuItem(
                        text = { Text(availableYear.toString()) },
                        onClick = {
                            expanded = false
                            onSelectYear(availableYear)
                        },
                        enabled = availableYear != year,
                    )
                }
            }
        }
        IconButton(
            onClick = { onSelectYear(availableYears[selectedIndex - 1]) },
            enabled = hasNext,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next year",
                tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
    }
    if (helperText != null) {
        Text(
            text = helperText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
        )
    }
    }
}

@Composable
private fun WrappedMonthSelector(
    month: MonthYear,
    availableMonths: List<MonthYear>,
    accessibilityLabel: String,
    onSelectMonth: (MonthYear) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSelectMonth = availableMonths.size > 1
    val selectedIndex = availableMonths.indexOf(month)
    val hasPrevious = selectedIndex >= 0 && selectedIndex < availableMonths.lastIndex
    val hasNext = selectedIndex > 0
    val helperText = wrappedPeriodSelectorHelperText(availableMonths.size, "months")

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = { onSelectMonth(availableMonths[selectedIndex + 1]) },
            enabled = hasPrevious,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous month",
                tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = canSelectMonth,
                modifier = Modifier.semantics {
                    contentDescription = accessibilityLabel
                },
            ) {
                Text(
                    text = month.toDisplayLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (canSelectMonth) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    },
                )
            }
            DropdownMenu(
                expanded = expanded && canSelectMonth,
                onDismissRequest = { expanded = false },
            ) {
                availableMonths.forEach { availableMonth ->
                    DropdownMenuItem(
                        text = { Text(availableMonth.toDisplayLabel()) },
                        onClick = {
                            expanded = false
                            onSelectMonth(availableMonth)
                        },
                        enabled = availableMonth != month,
                    )
                }
            }
        }
        IconButton(
            onClick = { onSelectMonth(availableMonths[selectedIndex - 1]) },
            enabled = hasNext,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next month",
                tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
    }
    if (helperText != null) {
        Text(
            text = helperText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
        )
    }
    }
}

// ── Page indicator ────────────────────────────────────────────────────────────

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            val dotSize by animateDpAsState(
                targetValue = if (isActive) 10.dp else 7.dp,
                animationSpec = if (reduceMotion) tween(durationMillis = 0) else tween(durationMillis = 200),
                label = "dot_size_$index",
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    ),
            )
        }
    }
}

// ── Card shell ────────────────────────────────────────────────────────────────

@Composable
private fun InsightCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 20.dp, end = 24.dp, top = 28.dp, bottom = 28.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                content()
            }
        }
    }
}

// ── Artwork card (entity-backed pages) ────────────────────────────────────────

@Composable
private fun WrappedArtworkCard(
    label: String,
    artworkUri: String?,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
    fallbackMotif: WrappedDecorativeMotif = WrappedDecorativeMotif.Patterns,
    content: @Composable ColumnScope.(artworkLoaded: Boolean) -> Unit,
) {
    val effectiveArtworkUri = artworkUri.takeIf { visualSettings.useArtworkBackgrounds }
    var artworkLoaded by remember(effectiveArtworkUri) { mutableStateOf(false) }
    val intensityStyle = visualSettings.backgroundIntensity.toVisualStyle()
    val fallbackPalette = visualSettings.fallbackTheme.toPalette(fallbackMotif, intensityStyle)
    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (effectiveArtworkUri != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                fallbackPalette.containerColor
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (effectiveArtworkUri != null) {
                AsyncImage(
                    model = effectiveArtworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { artworkLoaded = true },
                    onError = { artworkLoaded = false },
                )
            }
            Crossfade(
                targetState = artworkLoaded && effectiveArtworkUri != null,
                animationSpec = tween(durationMillis = 400),
                label = "artwork_crossfade",
            ) { showingArtwork ->
                if (showingArtwork) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = intensityStyle.artworkTopScrimAlpha),
                                1f to Color.Black.copy(alpha = intensityStyle.artworkBottomScrimAlpha),
                            )
                        )
                    )
                } else {
                    WrappedDecorativeBackground(
                        motif = fallbackMotif,
                        visualSettings = visualSettings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 28.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (artworkLoaded) Color.White.copy(alpha = 0.85f)
                             else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                content(artworkLoaded)
            }
        }
    }
}

// ── Decorative card (insight/stat pages without a safe artwork source) ─────────

private enum class WrappedDecorativeMotif { Milestones, Patterns }

@Composable
private fun WrappedDecorativeBackground(
    motif: WrappedDecorativeMotif,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
) {
    val intensityStyle = visualSettings.backgroundIntensity.toVisualStyle()
    val palette = visualSettings.fallbackTheme.toPalette(motif, intensityStyle)

    // Wrap in rememberUpdatedState so drawWithCache re-runs its cache when colors/multiplier change.
    val primaryState = rememberUpdatedState(palette.primary)
    val secondaryState = rememberUpdatedState(palette.secondary)
    val motifMultiplierState = rememberUpdatedState(intensityStyle.decorativeMotifMultiplier)

    Spacer(
        modifier = modifier.drawWithCache {
            val primary = primaryState.value
            val secondary = secondaryState.value
            val mult = motifMultiplierState.value

            // Build Path objects once per size change (the expensive part for Patterns).
            val wavePaths: List<Path>
            val waveAlphas: List<Float>
            val waveStrokeWidth: Float
            if (motif == WrappedDecorativeMotif.Patterns) {
                val amplitude = size.height * 0.065f
                val waveLen = size.width * 0.55f
                wavePaths = List(3) { w ->
                    val yBase = size.height * (0.30f + w * 0.22f)
                    val phase = w * (PI / 3.0)
                    Path().apply {
                        moveTo(0f, yBase.toFloat())
                        var x = 0f
                        while (x <= size.width + 3f) {
                            lineTo(x, (yBase + amplitude * sin(x / waveLen * 2.0 * PI + phase)).toFloat())
                            x += 3f
                        }
                    }
                }
                waveAlphas = List(3) { w ->
                    ((0.062f - w * 0.014f).coerceAtLeast(0.02f) * mult).coerceAtMost(0.15f)
                }
                waveStrokeWidth = 1.2.dp.toPx()
            } else {
                wavePaths = emptyList()
                waveAlphas = emptyList()
                waveStrokeWidth = 0f
            }

            // Milestones geometry (no Path needed — pure circles/lines)
            val ringCx = size.width * 0.87f
            val ringCy = size.height * 0.19f
            val ringStroke = 1.dp.toPx()
            val dotPositions = listOf(
                0.10f to 0.72f, 0.06f to 0.47f, 0.93f to 0.64f,
                0.77f to 0.90f, 0.52f to 0.94f, 0.30f to 0.84f,
            )

            // Patterns clock geometry
            val ccx = size.width * 0.90f
            val ccy = size.height * 0.83f
            val cr = 27.dp.toPx()
            val clockOutlineStroke = 0.8.dp.toPx()

            onDrawBehind {
                when (motif) {
                    WrappedDecorativeMotif.Milestones -> {
                        for (i in 1..5) {
                            drawCircle(
                                color = primary.copy(
                                    alpha = ((0.065f - i * 0.01f).coerceAtLeast(0.01f) * mult)
                                        .coerceAtMost(0.16f),
                                ),
                                radius = i * 50.dp.toPx(),
                                center = Offset(ringCx, ringCy),
                                style = Stroke(width = ringStroke),
                            )
                        }
                        val dotAlpha = (0.09f * mult).coerceAtMost(0.18f)
                        dotPositions.forEachIndexed { idx, (rx, ry) ->
                            drawCircle(
                                color = primary.copy(alpha = dotAlpha),
                                radius = (if (idx % 2 == 0) 3f else 2f).dp.toPx(),
                                center = Offset(rx * size.width, ry * size.height),
                            )
                        }
                    }
                    WrappedDecorativeMotif.Patterns -> {
                        wavePaths.forEachIndexed { w, path ->
                            drawPath(
                                path = path,
                                color = primary.copy(alpha = waveAlphas[w]),
                                style = Stroke(width = waveStrokeWidth),
                            )
                        }
                        drawCircle(
                            color = secondary.copy(alpha = (0.05f * mult).coerceAtMost(0.13f)),
                            radius = cr,
                            center = Offset(ccx, ccy),
                            style = Stroke(width = clockOutlineStroke),
                        )
                        val tickAlpha = (0.055f * mult).coerceAtMost(0.14f)
                        for (tick in 0..11) {
                            val angle = tick * (PI / 6.0) - PI / 2.0
                            val inner = if (tick % 3 == 0) 0.68f else 0.84f
                            val cosA = cos(angle).toFloat()
                            val sinA = sin(angle).toFloat()
                            drawLine(
                                color = secondary.copy(alpha = tickAlpha),
                                start = Offset(ccx + cr * inner * cosA, ccy + cr * inner * sinA),
                                end = Offset(ccx + cr * cosA, ccy + cr * sinA),
                                strokeWidth = (if (tick % 3 == 0) 1.1f else 0.7f).dp.toPx(),
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun WrappedDecorativeCard(
    label: String,
    motif: WrappedDecorativeMotif,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val intensityStyle = visualSettings.backgroundIntensity.toVisualStyle()
    val palette = visualSettings.fallbackTheme.toPalette(motif, intensityStyle)
    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = palette.containerColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            WrappedDecorativeBackground(
                motif = motif,
                visualSettings = visualSettings,
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 20.dp, end = 24.dp, top = 28.dp, bottom = 28.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(20.dp))
                    content()
                }
            }
        }
    }
}

// ── Shared primitives ─────────────────────────────────────────────────────────

/**
 * Returns an animated Int that counts up from 0 to [target] once [isVisible] becomes true.
 * Skips animation when [reduceMotion] is set. Safe across recompositions — will not re-animate
 * once triggered unless the composable leaves and re-enters the composition.
 */
@Composable
private fun animatedWrappedCounter(
    target: Int,
    isVisible: Boolean,
    reduceMotion: Boolean,
): Int {
    if (reduceMotion) return target
    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) triggered = true
    }
    val animated by animateIntAsState(
        targetValue = if (triggered) target else 0,
        animationSpec = tween(durationMillis = 700),
        label = "wrapped_counter",
    )
    return animated
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun NoDataText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

@Composable
private fun FeaturedTrack(
    song: SongStatsSummary,
    subline: String,
    caption: String,
    onDetailsClick: () -> Unit,
    onArtwork: Boolean = false,
) {
    val titleColor   = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor     = if (onArtwork) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val accentColor  = if (onArtwork) Color.White else MaterialTheme.colorScheme.primary
    val captionColor = if (onArtwork) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Text(
        text = song.song.displayTitle,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = titleColor,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = song.song.displayArtist.ifBlank { "Unknown Artist" },
        style = MaterialTheme.typography.bodyLarge,
        color = subColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = subline,
        style = MaterialTheme.typography.titleMedium,
        color = accentColor,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = caption,
        style = MaterialTheme.typography.bodySmall,
        color = captionColor,
    )
    Spacer(Modifier.height(16.dp))
    TextButton(
        onClick = onDetailsClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (onArtwork) Color.White else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text("View track details")
    }
}

// ── Pages ─────────────────────────────────────────────────────────────────────

@Composable
private fun IntroPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
) {
    val artworkUri = ArtworkResolver.albumArtworkUri(wrapped.mostPlayedSong?.song?.albumId)
        .takeIf { visualSettings.useArtworkBackgrounds }
    var artworkLoaded by remember(artworkUri) { mutableStateOf(false) }
    val intensityStyle = visualSettings.backgroundIntensity.toVisualStyle()
    val fallbackPalette = visualSettings.fallbackTheme.toPalette(
        motif = WrappedDecorativeMotif.Patterns,
        intensityStyle = intensityStyle,
    )

    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (artworkUri != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                fallbackPalette.containerColor
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (artworkUri != null) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { artworkLoaded = true },
                    onError = { artworkLoaded = false },
                )
            }
            Crossfade(
                targetState = artworkLoaded && artworkUri != null,
                animationSpec = tween(durationMillis = 400),
                label = "intro_artwork_crossfade",
            ) { showingArtwork ->
                if (showingArtwork) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Color.Black.copy(
                                alpha = (intensityStyle.artworkTopScrimAlpha + intensityStyle.artworkBottomScrimAlpha) / 2f,
                            ),
                        ),
                    )
                } else {
                    WrappedDecorativeBackground(
                        motif = WrappedDecorativeMotif.Patterns,
                        visualSettings = visualSettings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val onColor = if (artworkLoaded) Color.White
                          else MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = periodCopy.shortLabel,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = onColor,
                    )
                    Text(
                        text = periodCopy.introSubtitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Normal,
                        color = onColor.copy(alpha = 0.75f),
                    )
                }
                Column {
                    Text(
                        text = "Your music stayed with you.",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onColor,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No account. No cloud. No ads. Just your listening history, preserved on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColor.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    isVisible: Boolean = false,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animPlays         = animatedWrappedCounter(wrapped.totalPlayCount, isVisible, reduceMotion)
    val animDays          = animatedWrappedCounter(wrapped.listeningDaysCount, isVisible, reduceMotion)
    val animUniqueTracks  = animatedWrappedCounter(wrapped.uniqueSongsPlayedCount, isVisible, reduceMotion)
    val animUniqueArtists = animatedWrappedCounter(wrapped.uniqueArtistsPlayedCount, isVisible, reduceMotion)
    InsightCard(label = periodCopy.inReviewTitle, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BigStat(
                value = animPlays.toString(),
                label = "Plays",
                modifier = Modifier.weight(1f),
            )
            BigStat(
                value = StatisticsFormatters.formatDurationSummary(wrapped.totalListeningTimeMs),
                label = "Listening Time",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        StatRow("Listening Days", animDays.toString())
        StatRow("Unique Tracks", animUniqueTracks.toString())
        StatRow("Unique Artists", animUniqueArtists.toString())
        StatRow("Busiest Day", formatBusiestDay(wrapped.busiestDay, wrapped.busiestDayPlayCount))
        StatRow("Avg Plays / Day", formatDecimal(wrapped.averagePlaysPerActiveDay))
    }
}

@Composable
private fun StreaksPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    isVisible: Boolean = false,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animLongest = animatedWrappedCounter(wrapped.longestStreak, isVisible, reduceMotion)
    val animCurrent = animatedWrappedCounter(wrapped.currentStreak, isVisible, reduceMotion)
    InsightCard(
        label = "Listening Streaks · ${periodCopy.displayLabel}",
        modifier = modifier,
    ) {
        if (wrapped.longestStreak == 0) {
            NoDataText("No consecutive listening days recorded ${periodCopy.thisPeriod}. Play on multiple days to build a streak.")
        } else {
            BigStat(
                value = formatStreak(animLongest),
                label = "Longest streak",
            )
            Spacer(Modifier.height(24.dp))
            StatRow("Current streak", formatStreak(animCurrent))
        }
    }
}

@Composable
private fun PatternsPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
) {
    WrappedDecorativeCard(
        label = "Listening Patterns · ${periodCopy.displayLabel}",
        motif = WrappedDecorativeMotif.Patterns,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        val hasData = wrapped.mostActiveDayOfWeek != null || wrapped.mostActiveHour != null
        if (!hasData) {
            NoDataText("No listening patterns for ${periodCopy.thisPeriod}. Play music across days or hours to reveal patterns.")
        } else {
            wrapped.mostActiveDayOfWeek?.let { dow ->
                BigStat(value = formatDayOfWeek(dow), label = "Most active day")
                Spacer(Modifier.height(20.dp))
            }
            wrapped.mostActiveHour?.let { hour ->
                StatRow("Most active hour", formatHour(hour))
            }
            if (wrapped.listeningDaysCount > 0) {
                StatRow(
                    label = "Avg listening / day",
                    value = StatisticsFormatters.formatDurationSummary(
                        wrapped.averageListeningTimePerActiveDayMs,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TopTracksPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks = wrappedTopThree(wrapped.topSongs)
    RankedWrappedCard(
        label = "Top Tracks · ${periodCopy.shortLabel}",
        supportingCopy = "The tracks you returned to most ${periodCopy.thisPeriod}.",
        visualSettings = visualSettings,
        modifier = modifier,
        lowDataNote = wrappedRankedListNote(
            kind = WrappedRankedKind.TRACK,
            itemCount = tracks.size,
            periodCopy = periodCopy,
        ),
    ) {
        tracks.forEachIndexed { index, track ->
            RankedTrackRow(
                rank = index + 1,
                track = track,
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        }
    }
}

@Composable
private fun TopArtistsPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artists = wrappedTopThree(wrapped.topArtists)
    RankedWrappedCard(
        label = "Top Artists · ${periodCopy.shortLabel}",
        supportingCopy = "The artists that shaped your listening ${periodCopy.thisPeriod}.",
        visualSettings = visualSettings,
        modifier = modifier,
        lowDataNote = wrappedRankedListNote(
            kind = WrappedRankedKind.ARTIST,
            itemCount = artists.size,
            periodCopy = periodCopy,
        ),
    ) {
        artists.forEachIndexed { index, artist ->
            RankedArtistRow(
                rank = index + 1,
                artist = artist,
                onClick = { onArtistClick(artist.artistKey) },
            )
        }
    }
}

@Composable
private fun TopAlbumsPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums = wrappedTopThree(wrapped.topAlbums)
    RankedWrappedCard(
        label = "Top Albums · ${periodCopy.shortLabel}",
        supportingCopy = "The albums in your heaviest rotation ${periodCopy.thisPeriod}.",
        visualSettings = visualSettings,
        modifier = modifier,
        lowDataNote = wrappedRankedListNote(
            kind = WrappedRankedKind.ALBUM,
            itemCount = albums.size,
            periodCopy = periodCopy,
        ),
    ) {
        albums.forEachIndexed { index, album ->
            RankedAlbumRow(
                rank = index + 1,
                album = album,
                onClick = { onAlbumClick(album.albumKey) },
            )
        }
    }
}

@Composable
private fun SkipHabitsPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    visualSettings: WrappedVisualSettings,
    onTrackDetailsClick: (Long) -> Unit,
    isVisible: Boolean = false,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val animSkips    = animatedWrappedCounter(wrapped.totalSkipCount, isVisible, reduceMotion)
    val animSkipRate = animatedWrappedCounter(
        wrappedSkipRatePercent(wrapped.totalPlayCount, wrapped.totalSkipCount),
        isVisible,
        reduceMotion,
    )
    val track = wrapped.mostSkippedTrack
    WrappedDecorativeCard(
        label = "Skip Habits · ${periodCopy.shortLabel}",
        motif = WrappedDecorativeMotif.Patterns,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        Text(
            text = "How often you moved on ${periodCopy.thisPeriod}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BigStat(
                value = animSkips.toString(),
                label = "Total skips",
                modifier = Modifier.weight(1f),
            )
            BigStat(
                value = "$animSkipRate%",
                label = "Skip rate",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (track == null) {
            NoDataText("No skips recorded ${periodCopy.thisPeriod}.")
        } else {
            Text(
                text = "Most skipped track",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            RankedTrackRow(
                rank = null,
                track = track,
                metric = "${track.skipCount} skips",
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        }
    }
}

@Composable
private fun RankedWrappedCard(
    label: String,
    supportingCopy: String,
    visualSettings: WrappedVisualSettings,
    lowDataNote: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WrappedDecorativeCard(
        label = label,
        motif = WrappedDecorativeMotif.Patterns,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        Text(
            text = supportingCopy,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(14.dp))
        content()
        lowDataNote?.let { note ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            )
        }
    }
}

@Composable
private fun RankedTrackRow(
    rank: Int?,
    track: SongStatsSummary,
    metric: String = "${track.playCount} plays",
    onClick: () -> Unit,
) {
    RankedResultRow(
        rank = rank,
        title = track.song.displayTitle,
        subtitle = track.song.displayArtist.ifBlank { "Unknown Artist" },
        metric = metric,
        onClick = onClick,
        artwork = {
            ArtworkImage(
                artworkUri = ArtworkResolver.albumArtworkUri(track.song.albumId),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                placeholderIcon = Icons.Default.MusicNote,
            )
        },
    )
}

@Composable
private fun RankedArtistRow(
    rank: Int,
    artist: ArtistReportSummary,
    onClick: () -> Unit,
) {
    RankedResultRow(
        rank = rank,
        title = artist.artistKey,
        subtitle = "${artist.songCount} songs · ${artist.albumCount} albums",
        metric = "${artist.playCount} plays",
        onClick = onClick,
    )
}

@Composable
private fun RankedAlbumRow(
    rank: Int,
    album: AlbumReportSummary,
    onClick: () -> Unit,
) {
    RankedResultRow(
        rank = rank,
        title = album.albumKey,
        subtitle = album.artist.ifBlank { "Unknown Artist" },
        metric = "${album.playCount} plays",
        onClick = onClick,
    )
}

@Composable
private fun RankedResultRow(
    rank: Int?,
    title: String,
    subtitle: String,
    metric: String,
    onClick: () -> Unit,
    artwork: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rank?.let {
            Text(
                text = it.toString(),
                style = if (it == 1) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(22.dp),
                textAlign = TextAlign.Center,
            )
        }
        artwork?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (rank == 1) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = if (rank == 1) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = metric,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun RecentPlaysPage(
    wrapped: WrappedSummary,
    periodCopy: WrappedPeriodCopy,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(
        label = "Recent Plays · ${periodCopy.displayLabel}",
        modifier = modifier,
    ) {
        if (wrapped.recentlyPlayed.isEmpty()) {
            NoDataText("No recent plays for ${periodCopy.thisPeriod}. Play music ${periodCopy.duringThisPeriod} to fill this list.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(
                    items = wrapped.recentlyPlayed,
                    key = { "recent_${it.song.id}_${it.lastPlayedAt}" },
                ) { summary ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackDetailsClick(summary.song.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ArtworkImage(
                            artworkUri = ArtworkResolver.albumArtworkUri(summary.song.albumId),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = summary.song.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = summary.song.displayArtist.ifBlank { "Unknown Artist" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = StatisticsFormatters.formatLastPlayed(summary.lastPlayedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MilestonePage(
    milestones: List<WrappedMilestone>,
    periodLabel: String,
    visualSettings: WrappedVisualSettings,
    @Suppress("UNUSED_PARAMETER") reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    WrappedDecorativeCard(
        label = "Milestones · $periodLabel",
        motif = WrappedDecorativeMotif.Milestones,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        Text(
            text = "Moments from your $periodLabel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(16.dp))
        milestones.forEach { milestone ->
            MilestoneRow(milestone)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MilestoneRow(milestone: WrappedMilestone) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = milestone.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column {
            Text(
                text = milestone.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = milestone.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Milestone model + computation ─────────────────────────────────────────────

private data class WrappedMilestone(
    val icon: ImageVector,
    val label: String,
    val detail: String,
)

private fun computeMilestones(wrapped: WrappedSummary): List<WrappedMilestone> {
    val result = mutableListOf<WrappedMilestone>()

    val playMilestones = listOf(100, 500, 1_000, 5_000, 10_000)
    playMilestones.lastOrNull { it <= wrapped.totalPlayCount }?.let { threshold ->
        val label = when (threshold) {
            100     -> "First hundred plays"
            500     -> "Five hundred plays"
            1_000   -> "One thousand plays"
            5_000   -> "Five thousand plays"
            else    -> "Ten thousand plays"
        }
        result += WrappedMilestone(
            icon   = Icons.Default.MusicNote,
            label  = label,
            detail = "${wrapped.totalPlayCount} plays this year",
        )
    }

    val hourMs = 3_600_000L
    val timeMilestones = listOf(10L, 50L, 100L, 250L, 500L)
    timeMilestones.lastOrNull { it * hourMs <= wrapped.totalListeningTimeMs }?.let { threshold ->
        val label = when (threshold) {
            10L  -> "Ten-hour listener"
            50L  -> "Fifty-hour listener"
            100L -> "Hundred-hour listener"
            250L -> "Quarter-thousand hours"
            else -> "Five-hundred-hour listener"
        }
        result += WrappedMilestone(
            icon   = Icons.Default.Timer,
            label  = label,
            detail = StatisticsFormatters.formatDurationSummary(wrapped.totalListeningTimeMs) + " this year",
        )
    }

    val songMilestones = listOf(25, 50, 100, 250, 500)
    songMilestones.lastOrNull { it <= wrapped.uniqueSongsPlayedCount }?.let { threshold ->
        val label = when (threshold) {
            25  -> "Library explorer"
            50  -> "Broad taste"
            100 -> "Hundred-track discovery"
            250 -> "Deep explorer"
            else -> "Five-hundred-track library"
        }
        result += WrappedMilestone(
            icon   = Icons.Default.LibraryMusic,
            label  = label,
            detail = "${wrapped.uniqueSongsPlayedCount} different tracks played",
        )
    }

    val dayMilestones = listOf(7, 14, 30, 50, 100, 180, 365)
    dayMilestones.lastOrNull { it <= wrapped.listeningDaysCount }?.let { threshold ->
        val label = when (threshold) {
            7   -> "A week of music"
            14  -> "Two weeks of music"
            30  -> "A month of music"
            50  -> "Fifty listening days"
            100 -> "A hundred listening days"
            180 -> "Half a year of music"
            else -> "Every day, all year"
        }
        result += WrappedMilestone(
            icon   = Icons.Default.DateRange,
            label  = label,
            detail = "${wrapped.listeningDaysCount} days with music this year",
        )
    }

    return result
}

private fun formatCount(n: Int): String = when {
    n >= 1_000 -> "${n / 1_000}k"
    else       -> n.toString()
}

// ── Formatters ────────────────────────────────────────────────────────────────

private fun formatBusiestDay(day: LocalDate?, playCount: Int): String =
    day?.let { "${BUSIEST_DAY_FORMATTER.format(it)} · $playCount plays" } ?: "None"

private fun formatStreak(days: Int): String = if (days == 1) "1 day" else "$days days"

private fun formatDayOfWeek(dow: DayOfWeek): String =
    dow.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

private fun formatHour(hour: Int): String =
    DateTimeFormatter.ofPattern("h a", Locale.US).format(LocalTime.of(hour, 0))

private fun formatDecimal(value: Double): String {
    val s = String.format(Locale.US, "%.1f", value)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

private val BUSIEST_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)
