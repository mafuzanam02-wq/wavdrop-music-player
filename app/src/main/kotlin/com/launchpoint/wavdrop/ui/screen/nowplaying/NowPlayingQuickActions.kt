package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class NowPlayingQuickActionType { Favorite, Playlist, Timer, Share }

internal enum class NowPlayingQuickActionCorner { TopLeft, TopRight, BottomLeft, BottomRight }

internal data class NowPlayingQuickAction(
    val type: NowPlayingQuickActionType,
    val contentDescription: String,
    val selected: Boolean,
)

/** Fixed spatial model: each action always lives in the same artwork corner. */
internal fun NowPlayingQuickActionType.corner(): NowPlayingQuickActionCorner = when (this) {
    NowPlayingQuickActionType.Favorite -> NowPlayingQuickActionCorner.TopLeft
    NowPlayingQuickActionType.Playlist -> NowPlayingQuickActionCorner.TopRight
    NowPlayingQuickActionType.Timer    -> NowPlayingQuickActionCorner.BottomLeft
    NowPlayingQuickActionType.Share    -> NowPlayingQuickActionCorner.BottomRight
}

/**
 * Which track actions the Now Playing artwork corners offer. Empty when there is no current song.
 * External audio has no library identity, so Favorite and Playlist are withheld; Timer and Share remain.
 */
internal fun nowPlayingQuickActions(
    hasSong: Boolean,
    isExternalAudio: Boolean,
    isFavorite: Boolean,
    sleepTimerActive: Boolean,
): List<NowPlayingQuickAction> {
    if (!hasSong) return emptyList()
    return buildList {
        if (!isExternalAudio) {
            add(
                NowPlayingQuickAction(
                    type = NowPlayingQuickActionType.Favorite,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    selected = isFavorite,
                ),
            )
            add(
                NowPlayingQuickAction(
                    type = NowPlayingQuickActionType.Playlist,
                    contentDescription = "Add to playlist",
                    selected = false,
                ),
            )
        }
        add(
            NowPlayingQuickAction(
                type = NowPlayingQuickActionType.Timer,
                contentDescription = if (sleepTimerActive) "Sleep timer, active" else "Sleep timer",
                selected = sleepTimerActive,
            ),
        )
        add(
            NowPlayingQuickAction(
                type = NowPlayingQuickActionType.Share,
                contentDescription = "Share track",
                selected = false,
            ),
        )
    }
}

private val QuickActionTouchTarget = 48.dp
private val QuickActionBackingSize = 36.dp
private val QuickActionIconSize = 22.dp

/**
 * Overlays the actions on the artwork corners. Each control is a sibling of the artwork gesture
 * surface, so it only consumes taps inside its own 48dp target; the rest of the artwork keeps its
 * double-tap / long-press handling. [inset] is the gap between the artwork edge and the touch target.
 */
@Composable
internal fun BoxScope.NowPlayingArtworkCornerControls(
    actions: List<NowPlayingQuickAction>,
    inset: Dp,
    onAction: (NowPlayingQuickActionType) -> Unit,
) {
    actions.forEach { action ->
        val alignment = when (action.type.corner()) {
            NowPlayingQuickActionCorner.TopLeft     -> Alignment.TopStart
            NowPlayingQuickActionCorner.TopRight    -> Alignment.TopEnd
            NowPlayingQuickActionCorner.BottomLeft  -> Alignment.BottomStart
            NowPlayingQuickActionCorner.BottomRight -> Alignment.BottomEnd
        }
        NowPlayingCornerControl(
            action = action,
            onClick = { onAction(action.type) },
            modifier = Modifier.align(alignment).padding(inset),
        )
    }
}

@Composable
private fun NowPlayingCornerControl(
    action: NowPlayingQuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (action.type) {
        NowPlayingQuickActionType.Favorite ->
            if (action.selected) Icons.Default.Favorite else Icons.Default.FavoriteBorder
        NowPlayingQuickActionType.Playlist -> Icons.AutoMirrored.Filled.PlaylistAdd
        NowPlayingQuickActionType.Timer -> Icons.Default.Timer
        NowPlayingQuickActionType.Share -> Icons.Default.Share
    }
    val tint = if (action.selected) MaterialTheme.colorScheme.primary else Color.White
    Box(
        modifier = modifier
            .size(QuickActionTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = action.contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // Restrained, consistent scrim so the glyph reads over light, dark or busy artwork.
        Box(
            modifier = Modifier
                .size(QuickActionBackingSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(QuickActionIconSize),
            )
        }
    }
}
