package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WRAPPED_PAGE_COUNT = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrappedScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
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
            WrappedUiState.Empty -> EmptyContent(Modifier.padding(innerPadding))
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading Wrapped...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No event-backed Wrapped yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Play music in Wavdrop to create yearly event history. Imported aggregate counts are not used for Wrapped.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
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
    val years = state.availableYears
    val selectedIndex = years.indexOf(state.selectedYear)
    val wrapped = state.wrapped
    val pagerState = rememberPagerState(pageCount = { WRAPPED_PAGE_COUNT })

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
                text = "No matched plays found for ${wrapped.year}. Event history may point to tracks no longer in the library; play current library songs to rebuild it.",
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
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> OverviewPage(wrapped)
                1 -> StreaksPage(wrapped)
                2 -> PatternsPage(wrapped)
                3 -> TopTrackPage(wrapped, onTrackDetailsClick)
                4 -> TopArtistPage(wrapped, onArtistClick)
                5 -> TopAlbumPage(wrapped, onAlbumClick)
                6 -> MostSkippedPage(wrapped, onTrackDetailsClick)
                else -> RecentPlaysPage(wrapped, onTrackDetailsClick)
            }
        }

        PageIndicator(
            pageCount = WRAPPED_PAGE_COUNT,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 12.dp),
        )
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
                    .size(if (index == currentPage) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
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
) {
    Text(
        text = song.song.title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = song.song.artist.ifBlank { "Unknown Artist" },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = subline,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = caption,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onDetailsClick) {
        Text("View track details")
    }
}

// ── Pages ─────────────────────────────────────────────────────────────────────

@Composable
private fun OverviewPage(wrapped: WrappedSummary, modifier: Modifier = Modifier) {
    InsightCard(label = "${wrapped.year} Overview", modifier = modifier) {
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
private fun PatternsPage(wrapped: WrappedSummary, modifier: Modifier = Modifier) {
    InsightCard(label = "Listening Patterns", modifier = modifier) {
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
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(label = "Top Track", modifier = modifier) {
        val song = wrapped.mostPlayedSong
        if (song == null) {
            NoDataText("No tracks played this year yet. Play music in Wavdrop to choose a top track.")
        } else {
            FeaturedTrack(
                song = song,
                subline = "${song.playCount} plays",
                caption = "Your most-played track of ${wrapped.year}",
                onDetailsClick = { onTrackDetailsClick(song.song.id) },
            )
        }
    }
}

@Composable
private fun TopArtistPage(
    wrapped: WrappedSummary,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(label = "Top Artist", modifier = modifier) {
        val artist = wrapped.mostPlayedArtist
        if (artist == null) {
            NoDataText("No artists played this year yet. Play music in Wavdrop to choose a top artist.")
        } else {
            Text(
                text = artist.artistKey,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${artist.playCount} plays",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "You couldn't stop listening to ${artist.artistKey} this year",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onArtistClick(artist.artistKey) }) {
                Text("View artist")
            }
        }
    }
}

@Composable
private fun TopAlbumPage(
    wrapped: WrappedSummary,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(label = "Top Album", modifier = modifier) {
        val album = wrapped.mostPlayedAlbum
        if (album == null) {
            NoDataText("No albums played this year yet. Play music in Wavdrop to choose a top album.")
        } else {
            Text(
                text = album.albumKey,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${album.playCount} plays",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onAlbumClick(album.albumKey) }) {
                Text("View album")
            }
        }
    }
}

@Composable
private fun MostSkippedPage(
    wrapped: WrappedSummary,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightCard(label = "Most Skipped", modifier = modifier) {
        val track = wrapped.mostSkippedTrack
        if (track == null) {
            NoDataText("No skips recorded for this year. Skips during this year will appear here.")
        } else {
            Text(
                text = track.song.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = track.song.artist.ifBlank { "Unknown Artist" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${track.skipCount} skips",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onTrackDetailsClick(track.song.id) }) {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = summary.song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = summary.song.artist.ifBlank { "Unknown Artist" },
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
