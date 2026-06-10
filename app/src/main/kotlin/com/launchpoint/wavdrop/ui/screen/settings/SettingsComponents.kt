package com.launchpoint.wavdrop.ui.screen.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ── Constants ──────────────────────────────────────────────────────────────
private val ROW_HORIZONTAL_PADDING = 16.dp
private val ROW_VERTICAL_PADDING   = 14.dp
private const val SUBTITLE_ALPHA   = 0.65f

// ── Section structure ──────────────────────────────────────────────────────

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_HORIZONTAL_PADDING, end = ROW_HORIZONTAL_PADDING, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = ROW_HORIZONTAL_PADDING),
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        thickness = 0.5.dp,
    )
}

// ── Row types ──────────────────────────────────────────────────────────────

@Composable
internal fun ClickableSettingsRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier           = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun ToggleSettingsRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
        }
        Switch(
            checked         = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled         = enabled,
            modifier        = Modifier.padding(start = 12.dp),
        )
    }
}

// Radio-style row: title tints primary when selected for clear selection state.
@Composable
internal fun ScanModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
        }
    }
}

// Radio-style row for single-label choices (theme, icon). Name tints primary when selected.
@Composable
internal fun IconChoiceRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatchColor: Color? = null,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (swatchColor != null) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
                    .background(swatchColor, CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                        shape = CircleShape,
                    ),
            )
        }
        Text(
            text     = name,
            style    = MaterialTheme.typography.bodyLarge,
            color    = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ── Informational rows ─────────────────────────────────────────────────────

// Informational message below a section header, or inline status/error.
@Composable
internal fun SettingsMessageRow(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text     = message,
        style    = MaterialTheme.typography.bodySmall,
        color    = if (isError) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_HORIZONTAL_PADDING)
            .padding(top = 4.dp, bottom = 12.dp),
    )
}

// Inline status feedback after an export / backup action.
@Composable
internal fun ExportStatusRow(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text     = message,
        style    = MaterialTheme.typography.bodySmall,
        color    = if (isError) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_HORIZONTAL_PADDING)
            .padding(top = 4.dp, bottom = 12.dp),
    )
}

// ── Specialised rows ───────────────────────────────────────────────────────

@Composable
internal fun MinimumDurationRow(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        Text(
            text  = "Ignore audio shorter than $seconds seconds",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value                 = seconds.toFloat(),
            onValueChange         = { onSecondsChange(it.roundToInt().coerceIn(1, 60)) },
            onValueChangeFinished = onChangeFinished,
            valueRange            = 1f..60f,
            steps                 = 58,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "1 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
            Text(
                text  = "60 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
        }
    }
}

@Composable
internal fun SelectedFolderRow(
    folderUri: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decodedUri  = remember(folderUri) { Uri.decode(folderUri) ?: folderUri }
    val displayName = remember(decodedUri) {
        decodedUri
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { decodedUri }
    }
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .padding(start = ROW_HORIZONTAL_PADDING, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = decodedUri,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector        = Icons.Filled.Delete,
                contentDescription = "Remove selected folder",
                tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
internal fun AboutInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun DisabledSettingsRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(0.45f)
            .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBTITLE_ALPHA),
        )
    }
}
