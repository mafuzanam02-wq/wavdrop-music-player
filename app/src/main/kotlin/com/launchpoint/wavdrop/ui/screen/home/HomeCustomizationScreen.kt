package com.launchpoint.wavdrop.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRules
import com.launchpoint.wavdrop.data.settings.HomeSectionId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCustomizationScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeCustomizationViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Sections") },
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
                Text(
                    text     = "Choose which sections appear on your Home screen.",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            item {
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
            HomeLayoutSettingsRules.DISPLAY_ORDER.forEach { id ->
                item(key = id.name) {
                    HomeSectionToggleRow(
                        id          = id,
                        isVisible   = id in settings.visibleSections,
                        isToggleable = HomeLayoutSettingsRules.isToggleable(id),
                        onToggle    = { viewModel.setSectionVisible(id, it) },
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HomeSectionToggleRow(
    id: HomeSectionId,
    isVisible: Boolean,
    isToggleable: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .alpha(if (isToggleable) 1f else 0.55f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = HomeLayoutSettingsRules.displayNameFor(id),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = HomeLayoutSettingsRules.descriptionFor(id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked         = isVisible,
            onCheckedChange = if (isToggleable) onToggle else null,
        )
    }
}
