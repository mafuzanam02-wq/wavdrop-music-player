package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.RepeatMode

@Composable
fun MiniPlayer(
    nowPlaying: NowPlayingState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    modifier: Modifier = Modifier,
    applyNavigationBarsPadding: Boolean = true,
) {
    if (nowPlaying.song == null) return

    Surface(
        modifier       = modifier.fillMaxWidth(),
        color          = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
    ) {
        val columnModifier = if (applyNavigationBarsPadding) {
            Modifier.fillMaxWidth().navigationBarsPadding()
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = columnModifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (nowPlaying.durationMs > 0) {
                LinearProgressIndicator(
                    progress  = { (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) },
                    modifier  = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(bottom = 6.dp),
                    color      = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                )
            }
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNowPlaying),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    artworkUri         = ArtworkResolver.albumArtworkUri(nowPlaying.song.albumId),
                    contentDescription = "Album artwork for ${nowPlaying.song.album}",
                    placeholderIcon    = Icons.Default.MusicNote,
                    modifier           = Modifier.size(44.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text     = nowPlaying.song.title,
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = nowPlaying.song.artist,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector        = Icons.Default.Shuffle,
                        contentDescription = if (nowPlaying.shuffleEnabled) "Turn shuffle off" else "Turn shuffle on",
                        tint               = if (nowPlaying.shuffleEnabled) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector        = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint               = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector        = if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                        tint               = MaterialTheme.colorScheme.onSurface,
                        modifier           = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector        = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint               = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onCycleRepeatMode) {
                    Icon(
                        imageVector        = if (nowPlaying.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne
                                             else Icons.Default.Repeat,
                        contentDescription = when (nowPlaying.repeatMode) {
                            RepeatMode.OFF -> "Turn repeat all on"
                            RepeatMode.ALL -> "Turn repeat one on"
                            RepeatMode.ONE -> "Turn repeat off"
                        },
                        tint               = if (nowPlaying.repeatMode == RepeatMode.OFF)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
