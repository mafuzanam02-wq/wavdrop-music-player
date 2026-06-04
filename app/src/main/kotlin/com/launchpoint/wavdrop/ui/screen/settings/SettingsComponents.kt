package com.launchpoint.wavdrop.ui.screen.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = 16.dp),
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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

@Composable
internal fun IconChoiceRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text     = name,
            style    = MaterialTheme.typography.bodyLarge,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

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
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text  = "Ignore audio shorter than $seconds seconds",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value                = seconds.toFloat(),
            onValueChange        = { onSecondsChange(it.roundToInt().coerceIn(1, 60)) },
            onValueChangeFinished = onChangeFinished,
            valueRange           = 1f..60f,
            steps                = 58,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "1 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text  = "60 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector        = Icons.Filled.Delete,
                contentDescription = "Remove selected folder",
                tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

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
                   else MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    )
}

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
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    )
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
