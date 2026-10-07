package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerPhase
import com.launchpoint.wavdrop.playback.SleepTimerState

private const val CUSTOM_MIN_MINUTES = 1
private const val CUSTOM_MAX_MINUTES = 240

@Composable
fun SleepTimerDialog(
    state: SleepTimerState,
    /** [Boolean] is the "Finish current track" modifier; it only matters for the duration options. */
    onOptionSelected: (SleepTimerOption, Boolean) -> Unit,
    onCustomDurationSelected: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // A selection is shown only for a running countdown or the standalone "End of current song" boundary. Once a duration timer
    // has expired and is only waiting for the song to finish, no duration is selected (the countdown is over).
    val showSelection = state.phase == SleepTimerPhase.COUNTDOWN ||
        (state.phase == SleepTimerPhase.FINISHING_CURRENT_TRACK && state.option == SleepTimerOption.END_OF_CURRENT_SONG)
    val customActive = showSelection && state.customDurationMs != null
    var finishCurrentTrack by remember { mutableStateOf(state.phase == SleepTimerPhase.COUNTDOWN && state.finishCurrentTrack) }
    var customMinutesText by remember {
        mutableStateOf(state.customDurationMs?.takeIf { state.phase == SleepTimerPhase.COUNTDOWN }?.let { (it / 60_000L).toString() } ?: "")
    }

    val minutes = customMinutesText.toIntOrNull()
    val customError: String? = when {
        customMinutesText.isEmpty() -> null
        minutes == null -> "Enter a value from $CUSTOM_MIN_MINUTES to $CUSTOM_MAX_MINUTES minutes"
        minutes < CUSTOM_MIN_MINUTES || minutes > CUSTOM_MAX_MINUTES ->
            "Enter a value from $CUSTOM_MIN_MINUTES to $CUSTOM_MAX_MINUTES minutes"
        else -> null
    }
    val canSet = minutes != null && customError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column {
                if (state.phase == SleepTimerPhase.FINISHING_CURRENT_TRACK) {
                    Text(
                        text = "Finishing current track. Playback will stop when this song ends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SleepTimerOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option, finishCurrentTrack) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = showSelection && option == state.option && !customActive,
                            onClick = { onOptionSelected(option, finishCurrentTrack) },
                        )
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { finishCurrentTrack = !finishCurrentTrack }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Finish current track",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "After the timer ends, stop when the current song finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Switch(checked = finishCurrentTrack, onCheckedChange = { finishCurrentTrack = it })
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (customActive) Icons.Default.RadioButtonChecked
                                      else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (customActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { input ->
                            customMinutesText = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("Custom (1–240 min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = customError != null,
                        supportingText = {
                            if (customError != null) {
                                Text(customError, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(
                        onClick = { if (canSet) onCustomDurationSelected(minutes!! * 60_000L, finishCurrentTrack) },
                        enabled = canSet,
                    ) {
                        Text("Set")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
