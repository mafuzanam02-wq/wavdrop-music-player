package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.EqualizerCapabilities
import com.launchpoint.wavdrop.data.settings.EqualizerPlatformPreset
import com.launchpoint.wavdrop.data.settings.EqPresetType
import com.launchpoint.wavdrop.data.settings.WavdropPresetCatalog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsEqualizerScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val eqEnabled             by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqPresetType          by viewModel.eqPresetType.collectAsStateWithLifecycle()
    val eqPlatformPresetIndex by viewModel.eqPlatformPresetIndex.collectAsStateWithLifecycle()
    val eqCustomBandLevels    by viewModel.eqCustomBandLevels.collectAsStateWithLifecycle()
    val eqWavdropPresetId     by viewModel.eqWavdropPresetId.collectAsStateWithLifecycle()
    val eqCapabilities        by viewModel.eqCapabilities.collectAsStateWithLifecycle()
    var showPresetDialog by remember { mutableStateOf(false) }

    // Survive the one-frame null that collectAsStateWithLifecycle delivers on entry
    // before the hot StateFlow emits the cached real value.
    var rememberedCaps by remember { mutableStateOf<EqualizerCapabilities?>(null) }
    LaunchedEffect(eqCapabilities) {
        if (eqCapabilities != null) rememberedCaps = eqCapabilities
    }
    val caps = eqCapabilities ?: rememberedCaps ?: EQ_FALLBACK_CAPABILITIES

    val eqPresetLabel = when (eqPresetType) {
        EqPresetType.FLAT           -> "Flat"
        EqPresetType.WAVDROP_PRESET -> WavdropPresetCatalog.findById(eqWavdropPresetId)?.displayName ?: "Wavdrop"
        EqPresetType.PLATFORM       -> caps.platformPresets.getOrNull(eqPlatformPresetIndex ?: -1)?.name ?: "Flat"
        EqPresetType.CUSTOM         -> "Custom"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
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
                ToggleSettingsRow(
                    title           = "Equalizer",
                    subtitle        = "Tune Wavdrop's playback using your device's audio session.",
                    checked         = eqEnabled,
                    onCheckedChange = viewModel::setEqualizerEnabled,
                )
            }
            if (eqEnabled) {
                item {
                    ClickableSettingsRow(
                        title    = "Preset",
                        subtitle = eqPresetLabel,
                        onClick  = { showPresetDialog = true },
                    )
                }
                if (eqPresetType == EqPresetType.CUSTOM || eqPresetType == EqPresetType.WAVDROP_PRESET) {
                    item {
                        val initialLevels = if (eqPresetType == EqPresetType.WAVDROP_PRESET) {
                            WavdropPresetCatalog.findById(eqWavdropPresetId)
                                ?.bandLevels?.takeIf { it.size == caps.bandCount }
                                ?: List(caps.bandCount) { 0 }
                        } else {
                            eqCustomBandLevels
                        }
                        EqBandSection(
                            bandLevels         = initialLevels,
                            capabilities       = caps,
                            onBandLevelsChange = viewModel::setEqCustomBandLevels,
                        )
                    }
                } else {
                    item {
                        val helperText = if (eqPresetType == EqPresetType.PLATFORM) {
                            "System presets are managed by your device. Choose Custom or a Wavdrop preset to fine-tune bands."
                        } else {
                            "EQ is flat. Choose Custom or a Wavdrop preset to shape your sound."
                        }
                        SettingsMessageRow(message = helperText)
                    }
                }
            }
        }
    }

    if (showPresetDialog) {
        EqPresetDialog(
            presetType              = eqPresetType,
            wavdropPresetId         = eqWavdropPresetId,
            platformPresetIndex     = eqPlatformPresetIndex,
            capabilities            = caps,
            wavdropPresetsAvailable = caps.bandCount == 5,
            onSelectFlat            = { viewModel.setEqFlat(); showPresetDialog = false },
            onSelectCustom          = { viewModel.setEqCustomPreset(caps.bandCount); showPresetDialog = false },
            onSelectWavdropPreset   = { id -> viewModel.setEqWavdropPreset(id); showPresetDialog = false },
            onSelectPlatformPreset  = { index -> viewModel.setEqPlatformPreset(index); showPresetDialog = false },
            onDismiss               = { showPresetDialog = false },
        )
    }
}

// ── EQ capabilities — fallback used before AudioEnhancementController reports runtime values ──

internal val EQ_FALLBACK_CAPABILITIES = EqualizerCapabilities(
    bandCount           = 5,
    minLevelMb          = -1500,
    maxLevelMb          = 1500,
    centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000),
    platformPresets     = listOf(
        EqualizerPlatformPreset(0, "Normal"),
        EqualizerPlatformPreset(1, "Classical"),
        EqualizerPlatformPreset(2, "Dance"),
        EqualizerPlatformPreset(3, "Flat"),
        EqualizerPlatformPreset(4, "Folk"),
        EqualizerPlatformPreset(5, "Heavy Metal"),
        EqualizerPlatformPreset(6, "Hip Hop"),
        EqualizerPlatformPreset(7, "Jazz"),
        EqualizerPlatformPreset(8, "Pop"),
        EqualizerPlatformPreset(9, "Rock"),
    ),
)

internal fun formatCenterFreqHz(hz: Int): String {
    if (hz >= 1_000) {
        val khz = hz / 1_000f
        return if (khz % 1f == 0f) "${khz.toInt()} kHz" else "%.1f kHz".format(java.util.Locale.US, khz)
    }
    return "$hz Hz"
}

// Maps the 5 canonical S21 center frequencies to user-friendly band names.
// Falls back to the frequency string for any unknown Hz value.
internal fun eqBandLabel(freqHz: Int): String = when (freqHz) {
    60    -> "Bass"
    230   -> "Low Mid"
    910   -> "Mid"
    3600  -> "Presence"
    14000 -> "Air"
    else  -> formatCenterFreqHz(freqHz)
}

// Formats a millibel gain value as a human-readable dB string.
// Zero displays without a sign; positive values get a leading "+".
internal fun formatGainDb(levelMb: Int): String = when {
    levelMb > 0 -> "+%.1f dB".format(java.util.Locale.US, levelMb / 100f)
    levelMb < 0 -> "%.1f dB".format(java.util.Locale.US, levelMb / 100f)
    else        -> "0.0 dB"
}

private fun List<Int>.toEqLevels(bandCount: Int): List<Int> =
    if (size == bandCount) this else List(bandCount) { 0 }

// ── EQ band composables ──────────────────────────────────────────────────────

@Composable
private fun EqBandSection(
    bandLevels: List<Int>,
    capabilities: EqualizerCapabilities,
    onBandLevelsChange: (List<Int>) -> Unit,
) {
    var local by remember(bandLevels) { mutableStateOf(bandLevels.toEqLevels(capabilities.bandCount)) }

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        repeat(capabilities.bandCount) { index ->
            val freqHz = capabilities.centerFrequenciesHz.getOrElse(index) { 0 }
            EqBandRow(
                bandName           = eqBandLabel(freqHz),
                freqLabel          = formatCenterFreqHz(freqHz),
                levelMb            = local.getOrElse(index) { 0 },
                minLevelMb         = capabilities.minLevelMb,
                maxLevelMb         = capabilities.maxLevelMb,
                onLevelChange      = { new ->
                    local = local.toMutableList().also { it[index] = new }
                },
                onLevelChangeFinished = { onBandLevelsChange(local) },
            )
        }
    }
}

@Composable
private fun EqBandRow(
    bandName: String,
    freqLabel: String,
    levelMb: Int,
    minLevelMb: Int,
    maxLevelMb: Int,
    onLevelChange: (Int) -> Unit,
    onLevelChangeFinished: () -> Unit,
) {
    val stepSize = 100
    val steps = ((maxLevelMb - minLevelMb) / stepSize) - 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text  = bandName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = freqLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                )
            }
            Text(
                text  = formatGainDb(levelMb),
                style = MaterialTheme.typography.bodyMedium,
                color = if (levelMb == 0) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                        else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value                 = levelMb.toFloat(),
            onValueChange         = { raw ->
                val steps100 = (raw / stepSize).roundToInt()
                onLevelChange(steps100.coerceIn(minLevelMb / stepSize, maxLevelMb / stepSize) * stepSize)
            },
            onValueChangeFinished = onLevelChangeFinished,
            valueRange            = minLevelMb.toFloat()..maxLevelMb.toFloat(),
            steps                 = steps,
        )
    }
}

@Composable
private fun EqPresetDialog(
    presetType: EqPresetType,
    wavdropPresetId: String?,
    platformPresetIndex: Int?,
    capabilities: EqualizerCapabilities,
    wavdropPresetsAvailable: Boolean,
    onSelectFlat: () -> Unit,
    onSelectCustom: () -> Unit,
    onSelectWavdropPreset: (String) -> Unit,
    onSelectPlatformPreset: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preset") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 1. Reset EQ
                PresetDialogRow(
                    label    = "Reset EQ",
                    selected = presetType == EqPresetType.FLAT,
                    onClick  = onSelectFlat,
                )
                // 2. Custom
                PresetDialogRow(
                    label    = "Custom",
                    selected = presetType == EqPresetType.CUSTOM,
                    onClick  = onSelectCustom,
                )
                // 3. Wavdrop Presets (5-band devices only)
                if (wavdropPresetsAvailable) {
                    Text(
                        text     = "Wavdrop",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    WavdropPresetCatalog.all.forEach { preset ->
                        PresetDialogRow(
                            label       = preset.displayName,
                            description = preset.description,
                            selected    = presetType == EqPresetType.WAVDROP_PRESET && wavdropPresetId == preset.id,
                            onClick     = { onSelectWavdropPreset(preset.id) },
                        )
                    }
                }
                // 4. System Presets
                if (capabilities.platformPresets.isNotEmpty()) {
                    Text(
                        text     = "System",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    capabilities.platformPresets.forEach { preset ->
                        PresetDialogRow(
                            label    = preset.name,
                            selected = presetType == EqPresetType.PLATFORM && platformPresetIndex == preset.index.toInt(),
                            onClick  = { onSelectPlatformPreset(preset.index.toInt()) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PresetDialogRow(
    label: String,
    selected: Boolean,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
            }
        }
    }
}
