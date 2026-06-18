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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.ButtonDefaults
import coil.compose.AsyncImage
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
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
                onSelectYear = viewModel::selectYear,
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
                text = "Your year in music starts here.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Play music in Wavdrop and your private yearly recap will appear here.",
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
    onSelectYear: (Int) -> Unit,
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

    val years = state.availableYears
    val selectedIndex = years.indexOf(state.selectedYear)
    val wrapped = state.wrapped
    val visualSettings = WrappedVisualSettings(
        useArtworkBackgrounds = state.useArtworkBackgrounds,
        backgroundIntensity = state.backgroundIntensity,
        fallbackTheme = state.fallbackTheme,
    )

    val milestones = remember(wrapped) { computeMilestones(wrapped) }
    val showMilestonePage = state.showMilestoneCelebrations && milestones.isNotEmpty()
    val pageCount = if (showMilestonePage) WRAPPED_BASE_COUNT + 1 else WRAPPED_BASE_COUNT

    val pagerState = rememberPagerState(pageCount = { pageCount })
    var pagerBoundsInWindow by remember { mutableStateOf<AndroidRect?>(null) }

    LaunchedEffect(pageCount) {
    if (pageCount > 0 && pagerState.currentPage >= pageCount) {
        pagerState.scrollToPage(pageCount - 1)
    }
}

    Column(modifier = modifier.fillMaxSize()) {
        YearSelector(
            year = state.selectedYear,
            availableYears = years,
            hasPrevious = selectedIndex >= 0 && selectedIndex < years.lastIndex,
            hasNext = selectedIndex > 0,
            onSelectYear = onSelectYear,
            onPrevious = {
                if (selectedIndex >= 0 && selectedIndex < years.lastIndex)
                    onSelectYear(years[selectedIndex + 1])
            },
            onNext = {
                if (selectedIndex > 0) onSelectYear(years[selectedIndex - 1])
            },
        )

        if (wrapped.emptyState.isEmpty) {
            Text(
                text = "No listening summary found for ${wrapped.year}. Play current library songs to build a fresh yearly summary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

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
            when (page) {
                0    -> IntroPage(wrapped, visualSettings)
                1    -> OverviewPage(wrapped)
                2    -> StreaksPage(wrapped)
                3    -> PatternsPage(wrapped, visualSettings)
                4    -> TopTrackPage(wrapped, visualSettings, onTrackDetailsClick)
                5    -> TopArtistPage(wrapped, visualSettings, onArtistClick)
                6    -> TopAlbumPage(wrapped, visualSettings, onAlbumClick)
                7    -> MostSkippedPage(wrapped, visualSettings, onTrackDetailsClick)
                8    -> RecentPlaysPage(wrapped, onTrackDetailsClick)
                else -> if (showMilestonePage) MilestonePage(milestones, wrapped.year, visualSettings, reduceMotion)
                        else RecentPlaysPage(wrapped, onTrackDetailsClick)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            PageIndicator(
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = {
                    val rect = pagerBoundsInWindow ?: return@IconButton
                    val act = activity ?: return@IconButton
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

// ── Year selector ─────────────────────────────────────────────────────────────

@Composable
private fun YearSelector(
    year: Int,
    availableYears: List<Int>,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSelectYear: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSelectYear = availableYears.size > 1

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
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
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next year",
                tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
    }
}

// ── Page indicator ────────────────────────────────────────────────────────────

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) MaterialTheme.colorScheme.primary
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
            if (artworkLoaded && effectiveArtworkUri != null) {
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
    val primary = palette.primary
    val secondary = palette.secondary
    val motifMultiplier = intensityStyle.decorativeMotifMultiplier
    Canvas(modifier = modifier) {
        when (motif) {
            WrappedDecorativeMotif.Milestones -> {
                // Concentric badge rings anchored top-right
                val cx = size.width * 0.87f
                val cy = size.height * 0.19f
                for (i in 1..5) {
                    drawCircle(
                        color = primary.copy(
                            alpha = ((0.065f - i * 0.01f).coerceAtLeast(0.01f) * motifMultiplier)
                                .coerceAtMost(0.16f),
                        ),
                        radius = i * 50.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                // Scattered accent dots
                val dots = listOf(
                    0.10f to 0.72f, 0.06f to 0.47f, 0.93f to 0.64f,
                    0.77f to 0.90f, 0.52f to 0.94f, 0.30f to 0.84f,
                )
                dots.forEachIndexed { idx, (rx, ry) ->
                    val r = if (idx % 2 == 0) 3f else 2f
                    drawCircle(
                        color = primary.copy(alpha = (0.09f * motifMultiplier).coerceAtMost(0.18f)),
                        radius = r.dp.toPx(),
                        center = Offset(rx * size.width, ry * size.height),
                    )
                }
            }
            WrappedDecorativeMotif.Patterns -> {
                // Layered sine waves (listening rhythm motif)
                val amplitude = size.height * 0.065f
                val waveLen = size.width * 0.55f
                for (w in 0..2) {
                    val yBase = size.height * (0.30f + w * 0.22f)
                    val phase = w * (PI / 3.0)
                    val path = Path()
                    path.moveTo(0f, yBase.toFloat())
                    var x = 0f
                    while (x <= size.width + 3f) {
                        val y = (yBase + amplitude * sin(x / waveLen * 2.0 * PI + phase)).toFloat()
                        path.lineTo(x, y)
                        x += 3f
                    }
                    drawPath(
                        path = path,
                        color = primary.copy(
                            alpha = ((0.062f - w * 0.014f).coerceAtLeast(0.02f) * motifMultiplier)
                                .coerceAtMost(0.15f),
                        ),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
                // Mini clock-face motif bottom-right
                val ccx = size.width * 0.90f
                val ccy = size.height * 0.83f
                val cr = 27.dp.toPx()
                drawCircle(
                    color = secondary.copy(alpha = (0.05f * motifMultiplier).coerceAtMost(0.13f)),
                    radius = cr,
                    center = Offset(ccx, ccy),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
                for (tick in 0..11) {
                    val angle = tick * (PI / 6.0) - PI / 2.0
                    val inner = if (tick % 3 == 0) 0.68f else 0.84f
                    drawLine(
                        color = secondary.copy(alpha = (0.055f * motifMultiplier).coerceAtMost(0.14f)),
                        start = Offset(
                            (ccx + cr * inner * cos(angle)).toFloat(),
                            (ccy + cr * inner * sin(angle)).toFloat(),
                        ),
                        end = Offset(
                            (ccx + cr * cos(angle)).toFloat(),
                            (ccy + cr * sin(angle)).toFloat(),
                        ),
                        strokeWidth = (if (tick % 3 == 0) 1.1 else 0.7).dp.toPx(),
                    )
                }
            }
        }
    }
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
            if (artworkLoaded && artworkUri != null) {
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
                        text = wrapped.year.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = onColor,
                    )
                    Text(
                        text = "in Review",
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
private fun OverviewPage(wrapped: WrappedSummary, modifier: Modifier = Modifier) {
    InsightCard(label = "${wrapped.year} in Review", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BigStat(
                value = wrapped.totalPlayCount.toString(),
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
        StatRow("Listening Days", wrapped.listeningDaysCount.toString())
        StatRow("Unique Tracks", wrapped.uniqueSongsPlayedCount.toString())
        StatRow("Unique Artists", wrapped.uniqueArtistsPlayedCount.toString())
        StatRow("Busiest Day", formatBusiestDay(wrapped.busiestDay, wrapped.busiestDayPlayCount))
        StatRow("Avg Plays / Day", formatDecimal(wrapped.averagePlaysPerActiveDay))
    }
}

@Composable
private fun StreaksPage(wrapped: WrappedSummary, modifier: Modifier = Modifier) {
    InsightCard(label = "Listening Streaks", modifier = modifier) {
        if (wrapped.longestStreak == 0) {
            NoDataText("No consecutive listening days recorded for this year. Play on multiple days to build a streak.")
        } else {
            BigStat(
                value = formatStreak(wrapped.longestStreak),
                label = "Longest streak",
            )
            Spacer(Modifier.height(24.dp))
            StatRow("Current streak", formatStreak(wrapped.currentStreak))
        }
    }
}

@Composable
private fun PatternsPage(
    wrapped: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    modifier: Modifier = Modifier,
) {
    WrappedDecorativeCard(
        label = "Listening Patterns",
        motif = WrappedDecorativeMotif.Patterns,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        val hasData = wrapped.mostActiveDayOfWeek != null || wrapped.mostActiveHour != null
        if (!hasData) {
            NoDataText("No listening patterns for this year. Play music across days or hours to reveal patterns.")
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
private fun TopTrackPage(
    wrapped: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = wrapped.mostPlayedSong
    WrappedArtworkCard(
        label = "Top Track",
        artworkUri = ArtworkResolver.albumArtworkUri(song?.song?.albumId),
        visualSettings = visualSettings,
        modifier = modifier,
    ) { artworkLoaded ->
        if (song == null) {
            NoDataText("No tracks played this year yet. Play music in Wavdrop to choose a top track.")
        } else {
            ArtworkImage(
                artworkUri = ArtworkResolver.albumArtworkUri(song.song.albumId),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                placeholderIcon = Icons.Default.MusicNote,
            )
            Spacer(Modifier.height(16.dp))
            FeaturedTrack(
                song = song,
                subline = "${song.playCount} plays",
                caption = "Your most-played track of ${wrapped.year}",
                onDetailsClick = { onTrackDetailsClick(song.song.id) },
                onArtwork = artworkLoaded,
            )
        }
    }
}

@Composable
private fun TopArtistPage(
    wrapped: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artist = wrapped.mostPlayedArtist
    // Resolve representative artwork: first recently-played song by this artist
    val artworkUri = remember(wrapped) {
        if (artist == null) return@remember null
        wrapped.recentlyPlayed
            .firstOrNull { it.song.artist.trim() == artist.artistKey }
            ?.let { ArtworkResolver.albumArtworkUri(it.song.albumId) }
            ?: if (wrapped.mostPlayedSong?.song?.artist?.trim() == artist.artistKey)
                ArtworkResolver.albumArtworkUri(wrapped.mostPlayedSong?.song?.albumId) else null
    }
    WrappedArtworkCard(
        label = "Top Artist",
        artworkUri = artworkUri,
        visualSettings = visualSettings,
        modifier = modifier,
    ) { artworkLoaded ->
        if (artist == null) {
            NoDataText("No artists played this year yet. Play music in Wavdrop to choose a top artist.")
        } else {
            val titleColor   = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.onSurface
            val accentColor  = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary
            val captionColor = if (artworkLoaded) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            Text(
                text = artist.artistKey,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${artist.playCount} plays",
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "You couldn't stop listening to ${artist.artistKey} this year",
                style = MaterialTheme.typography.bodySmall,
                color = captionColor,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { onArtistClick(artist.artistKey) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("View artist")
            }
        }
    }
}

@Composable
private fun TopAlbumPage(
    wrapped: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val album = wrapped.mostPlayedAlbum
    // albumKey == song.album.trim() — match via recentlyPlayed, fall back to mostPlayedSong
    val artworkUri = remember(wrapped) {
        if (album == null) return@remember null
        wrapped.recentlyPlayed
            .firstOrNull { it.song.album.trim() == album.albumKey }
            ?.let { ArtworkResolver.albumArtworkUri(it.song.albumId) }
            ?: ArtworkResolver.albumArtworkUri(wrapped.mostPlayedSong?.song?.albumId)
    }
    WrappedArtworkCard(
        label = "Top Album",
        artworkUri = artworkUri,
        visualSettings = visualSettings,
        modifier = modifier,
    ) { artworkLoaded ->
        if (album == null) {
            NoDataText("No albums played this year yet. Play music in Wavdrop to choose a top album.")
        } else {
            val titleColor   = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.onSurface
            val subColor     = if (artworkLoaded) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            val accentColor  = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary
            ArtworkImage(
                artworkUri = artworkUri,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = album.albumKey,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${album.playCount} plays",
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { onAlbumClick(album.albumKey) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("View album")
            }
        }
    }
}

@Composable
private fun MostSkippedPage(
    wrapped: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = wrapped.mostSkippedTrack
    WrappedArtworkCard(
        label = "Most Skipped",
        artworkUri = ArtworkResolver.albumArtworkUri(track?.song?.albumId),
        visualSettings = visualSettings,
        modifier = modifier,
    ) { artworkLoaded ->
        if (track == null) {
            NoDataText("No skips recorded for this year. Skips during this year will appear here.")
        } else {
            val titleColor   = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.onSurface
            val subColor     = if (artworkLoaded) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            val accentColor  = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary
            Text(
                text = track.song.displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = track.song.displayArtist.ifBlank { "Unknown Artist" },
                style = MaterialTheme.typography.bodyLarge,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${track.skipCount} skips",
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { onTrackDetailsClick(track.song.id) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (artworkLoaded) Color.White else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("View track details")
            }
        }
    }
}

@Composable
private fun RecentPlaysPage(
    wrapped: WrappedSummary,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(label = "Recent Plays", modifier = modifier) {
        if (wrapped.recentlyPlayed.isEmpty()) {
            NoDataText("No recent plays for this year. Play music during this year to fill this list.")
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
    year: Int,
    visualSettings: WrappedVisualSettings,
    @Suppress("UNUSED_PARAMETER") reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    WrappedDecorativeCard(
        label = "Milestones",
        motif = WrappedDecorativeMotif.Milestones,
        visualSettings = visualSettings,
        modifier = modifier,
    ) {
        Text(
            text = "Moments from your $year.",
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
