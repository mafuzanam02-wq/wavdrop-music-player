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
import com.launchpoint.wavdrop.playback.SleepTimerState

private const val CUSTOM_MIN_MINUTES = 1
private const val CUSTOM_MAX_MINUTES = 240

@Composable
fun SleepTimerDialog(
    state: SleepTimerState,
    onOptionSelected: (SleepTimerOption) -> Unit,
    onCustomDurationSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val customActive = state.customDurationMs != null
    var customMinutesText by remember {
        mutableStateOf(state.customDurationMs?.let { (it / 60_000L).toString() } ?: "")
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
                SleepTimerOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == state.option && !customActive,
                            onClick = { onOptionSelected(option) },
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
                        onClick = { if (canSet) onCustomDurationSelected(minutes!! * 60_000L) },
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
