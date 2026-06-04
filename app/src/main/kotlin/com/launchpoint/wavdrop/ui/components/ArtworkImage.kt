package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

@Composable
fun ArtworkImage(
    artworkUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shortestSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val placeholderSize = (shortestSide * 0.42f).coerceIn(22.dp, 72.dp)
        if (artworkUri.isNullOrBlank()) {
            ArtworkPlaceholder(
                placeholderIcon = placeholderIcon,
                modifier = Modifier.size(placeholderSize),
            )
        } else {
            SubcomposeAsyncImage(
                model = artworkUri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (painter.state is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    ArtworkPlaceholder(
                        placeholderIcon = placeholderIcon,
                        modifier = Modifier.size(placeholderSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkPlaceholder(
    placeholderIcon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = placeholderIcon ?: Icons.Default.Album,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
