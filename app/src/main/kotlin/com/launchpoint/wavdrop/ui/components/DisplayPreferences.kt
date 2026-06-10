package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode

val LocalShowSongThumbnails       = compositionLocalOf { true }
val LocalShowAlbumInSongRows      = compositionLocalOf { false }
val LocalArtworkCornerStyle       = compositionLocalOf { ArtworkCornerStyle.ROUNDED }
val LocalNowPlayingBackground     = compositionLocalOf { NowPlayingBackground.ARTWORK }
val LocalShowQueueCount           = compositionLocalOf { true }
val LocalNowPlayingTimeDisplayMode = compositionLocalOf { NowPlayingTimeDisplayMode.DURATION }

fun ArtworkCornerStyle.toShape(): Shape = when (this) {
    ArtworkCornerStyle.SOFT    -> RoundedCornerShape(4.dp)
    ArtworkCornerStyle.ROUNDED -> RoundedCornerShape(8.dp)
    ArtworkCornerStyle.SQUARE  -> RoundedCornerShape(0.dp)
}
