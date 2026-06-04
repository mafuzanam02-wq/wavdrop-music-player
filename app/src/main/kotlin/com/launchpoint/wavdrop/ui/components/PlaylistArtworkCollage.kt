package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun PlaylistArtworkCollage(
    artworkUris: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val uris = artworkUris.filter { it.isNotBlank() }.distinct().take(4)
    val tileShape = RoundedCornerShape(0.dp)
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        when (uris.size) {
            0 -> ArtworkImage(
                artworkUri = null,
                contentDescription = contentDescription,
                placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                modifier = Modifier.fillMaxSize(),
            )
            1 -> ArtworkImage(
                artworkUri = uris.first(),
                contentDescription = contentDescription,
                placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                modifier = Modifier.fillMaxSize(),
            )
            2 -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                uris.forEach { uri ->
                    ArtworkImage(
                        artworkUri = uri,
                        contentDescription = contentDescription,
                        placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                        shape = tileShape,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val rows = uris.chunked(2)
                rows.forEach { rowUris ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rowUris.forEach { uri ->
                            ArtworkImage(
                                artworkUri = uri,
                                contentDescription = contentDescription,
                                placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                                shape = tileShape,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                        if (rowUris.size == 1) {
                            ArtworkImage(
                                artworkUri = null,
                                contentDescription = null,
                                placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                                shape = tileShape,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
