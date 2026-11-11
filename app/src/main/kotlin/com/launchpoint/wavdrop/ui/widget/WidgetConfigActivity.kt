package com.launchpoint.wavdrop.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.ui.screen.settings.CardInternalDivider
import com.launchpoint.wavdrop.ui.screen.settings.ScanModeRow
import com.launchpoint.wavdrop.ui.screen.settings.SectionHeader
import com.launchpoint.wavdrop.ui.screen.settings.SettingsGroupCard
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-widget appearance screen. Launched by the launcher when a widget is added (and, where the launcher offers it, when the user
 * reconfigures a widget). Follows the widget configuration contract: RESULT_CANCELED (with the id) is set up front so backing
 * out or tapping Cancel leaves nothing saved and cancels adding the widget; RESULT_OK is returned only after Save.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var appSettingsRepository: AppSettingsRepository
    @Inject lateinit var widgetAppearanceStore: WidgetAppearanceStore

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // The appearance the screen opened with: what a failed or abandoned Save is rolled back to.
    private var previous = WidgetAppearance.DEFAULT
    private var saving by mutableStateOf(false)
    private var saveFailed by mutableStateOf(false)
    private val saver by lazy {
        WidgetConfigSaver(
            widgetAppearanceStore,
            renderFirstUpdate = { id -> WavdropWidgetUpdater.requestUpdateAndAwait(applicationContext, id) },
            reconcile = { id -> WavdropWidgetUpdater.requestUpdate(applicationContext, listOf(id)) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appWidgetId = WidgetConfigContract.widgetIdFrom(intent)
        val ours = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WavdropWidgetProvider::class.java))
        if (!WidgetConfigContract.isUsable(appWidgetId, ours)) {
            // Missing, invalid, or someone else's id: nothing to configure.
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setResult(Activity.RESULT_CANCELED, WidgetConfigContract.resultData(appWidgetId))

        var initial by mutableStateOf<WidgetAppearance?>(null)
        lifecycleScope.launch {
            val stored = widgetAppearanceStore.get(appWidgetId)
            previous = stored
            initial = stored
        }

        setContent {
            val themeMode by appSettingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accentColor by appSettingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.MIDNIGHT_VIOLET)
            WavdropTheme(themeMode = themeMode, accentColor = accentColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    initial?.let { start ->
                        WidgetAppearanceScreen(start = start, saving = saving, saveFailed = saveFailed, onSave = ::save, onCancel = ::cancel)
                    }
                }
            }
        }
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED, WidgetConfigContract.resultData(appWidgetId))
        finish()
    }

    private fun save(appearance: WidgetAppearance) {
        if (saving) return // duplicate taps while a save is in flight are ignored (the saver also refuses them)
        saving = true
        saveFailed = false
        val id = appWidgetId
        lifecycleScope.launch {
            // Persist -> wait for the first render of THIS widget -> only then report RESULT_OK. On failure/timeout the previous
            // appearance is restored, the result stays RESULT_CANCELED and the user can retry or cancel.
            when (saver.commit(id, appearance, previous)) {
                WidgetConfigSaver.Result.Saved -> {
                    setResult(Activity.RESULT_OK, WidgetConfigContract.resultData(id))
                    finish()
                }
                WidgetConfigSaver.Result.Failed -> {
                    saving = false
                    saveFailed = true
                }
                WidgetConfigSaver.Result.Busy -> Unit
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetAppearanceScreen(
    start: WidgetAppearance,
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (WidgetAppearance) -> Unit,
    onCancel: () -> Unit,
) {
    var style by remember { mutableStateOf(start.style) }
    var intensity by remember { mutableStateOf(start.intensity) }
    val current = WidgetAppearance(style, intensity)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))

        SectionHeader(title = stringResource(R.string.widget_config_background))
        SettingsGroupCard {
            ScanModeRow(
                title = stringResource(R.string.widget_config_style_artwork),
                subtitle = stringResource(R.string.widget_config_style_artwork_hint),
                selected = style == WidgetBackgroundStyle.ARTWORK,
                onClick = { style = WidgetBackgroundStyle.ARTWORK },
            )
            CardInternalDivider()
            ScanModeRow(
                title = stringResource(R.string.widget_config_style_solid),
                subtitle = stringResource(R.string.widget_config_style_solid_hint),
                selected = style == WidgetBackgroundStyle.SOLID,
                onClick = { style = WidgetBackgroundStyle.SOLID },
            )
        }

        if (current.intensityApplies) {
            SectionHeader(title = stringResource(R.string.widget_config_intensity))
            SettingsGroupCard {
                ScanModeRow(
                    title = stringResource(R.string.widget_config_intensity_light),
                    subtitle = stringResource(R.string.widget_config_intensity_light_hint),
                    selected = intensity == WidgetArtworkIntensity.LIGHT,
                    onClick = { intensity = WidgetArtworkIntensity.LIGHT },
                )
                CardInternalDivider()
                ScanModeRow(
                    title = stringResource(R.string.widget_config_intensity_balanced),
                    subtitle = stringResource(R.string.widget_config_intensity_balanced_hint),
                    selected = intensity == WidgetArtworkIntensity.BALANCED,
                    onClick = { intensity = WidgetArtworkIntensity.BALANCED },
                )
                CardInternalDivider()
                ScanModeRow(
                    title = stringResource(R.string.widget_config_intensity_dark),
                    subtitle = stringResource(R.string.widget_config_intensity_dark_hint),
                    selected = intensity == WidgetArtworkIntensity.DARK,
                    onClick = { intensity = WidgetArtworkIntensity.DARK },
                )
            }
        }

        if (saveFailed) {
            Text(
                text = stringResource(R.string.widget_config_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.widget_config_cancel))
            }
            Button(onClick = { onSave(current) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (saving) R.string.widget_config_saving else R.string.widget_config_save))
            }
        }
    }
}
