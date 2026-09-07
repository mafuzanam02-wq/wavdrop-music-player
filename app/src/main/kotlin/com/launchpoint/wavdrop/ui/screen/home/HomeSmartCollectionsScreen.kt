package com.launchpoint.wavdrop.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.screen.settings.SectionDivider
import com.launchpoint.wavdrop.ui.screen.settings.SectionHeader
import com.launchpoint.wavdrop.ui.screen.settings.SettingsMessageRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSmartCollectionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeSmartCollectionsViewModel = hiltViewModel(),
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()

    // Which selected collection a tapped "Other" collection would replace.
    var replacementFor by remember { mutableStateOf<SmartCollectionType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Collections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item {
                SettingsMessageRow(
                    message = "Choose which 3 Smart Collections appear on Home, and the order they show in.",
                )
            }

            item {
                SelectedSectionHeader(selectedCount = selection.size)
            }
            itemsIndexed(selection) { index, type ->
                SelectedCollectionRow(
                    type       = type,
                    position   = index + 1,
                    total      = selection.size,
                    canMoveUp  = index > 0,
                    canMoveDown = index < selection.lastIndex,
                    onMoveUp   = { viewModel.moveUp(index) },
                    onMoveDown = { viewModel.moveDown(index) },
                )
            }

            if (available.isNotEmpty()) {
                item { SectionDivider() }
                item { SectionHeader("Other Collections") }
                item {
                    SettingsMessageRow(
                        message = "Tap a collection to swap it in for one of your selected three.",
                    )
                }
                items(available) { type ->
                    AvailableCollectionRow(
                        type    = type,
                        onClick = { replacementFor = type },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    val incoming = replacementFor
    if (incoming != null) {
        ReplaceCollectionDialog(
            incoming  = incoming,
            selected  = selection,
            onReplace = { existing ->
                viewModel.replace(existing = existing, replacement = incoming)
                replacementFor = null
            },
            onDismiss = { replacementFor = null },
        )
    }
}

@Composable
private fun SelectedSectionHeader(selectedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "Selected for Home",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            text     = "$selectedCount of 3 selected",
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$selectedCount of 3 collections selected"
            },
        )
    }
}

@Composable
private fun SelectedCollectionRow(
    type: SmartCollectionType,
    position: Int,
    total: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val compact = LocalCompactMode.current
    val name = SmartCollectionBuilder.titleFor(type)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (compact) 3.dp else 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = if (compact) 6.dp else 10.dp, bottom = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Announce the Home order for TalkBack; the move buttons keep their own labels.
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$name, selected for Home, position $position of $total"
                    },
            ) {
                Text(
                    text  = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!compact) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text  = SmartCollectionBuilder.descriptionFor(type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(
                    imageVector        = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move $name up",
                    modifier           = Modifier.size(22.dp),
                    tint               = if (canMoveUp) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(
                    imageVector        = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move $name down",
                    modifier           = Modifier.size(22.dp),
                    tint               = if (canMoveDown) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                )
            }
        }
    }
}

@Composable
private fun AvailableCollectionRow(
    type: SmartCollectionType,
    onClick: () -> Unit,
) {
    val compact = LocalCompactMode.current
    val name = SmartCollectionBuilder.titleFor(type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!compact) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = SmartCollectionBuilder.descriptionFor(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Icon(
            imageVector        = Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier           = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReplaceCollectionDialog(
    incoming: SmartCollectionType,
    selected: List<SmartCollectionType>,
    onReplace: (SmartCollectionType) -> Unit,
    onDismiss: () -> Unit,
) {
    val incomingName = SmartCollectionBuilder.titleFor(incoming)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $incomingName") },
        text = {
            Column {
                Text(
                    text  = "Replace which collection?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(8.dp))
                selected.forEach { existing ->
                    val existingName = SmartCollectionBuilder.titleFor(existing)
                    Text(
                        text  = existingName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableRow { onReplace(existing) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// Small local helper keeps the row call sites readable.
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable { onClick() }
