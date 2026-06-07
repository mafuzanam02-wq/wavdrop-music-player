package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val BETA_3_ITEMS = listOf(
    "Refreshed launcher icons",
    "Improved privacy, legal, and Play Store readiness wording",
    "Added Track Details access from Now Playing",
    "Improved Home dashboard song actions",
    "Kept Delete safely inside Track Details only",
    "Added Play Store readiness and listing draft documents",
)

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("What's new in Beta 3") },
        text             = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                BETA_3_ITEMS.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(
                        text  = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}
