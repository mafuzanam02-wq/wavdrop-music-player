package com.launchpoint.wavdrop

import android.app.Application
import com.launchpoint.wavdrop.ui.widget.WavdropWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WavdropApp : Application() {

    @Inject lateinit var widgetUpdater: WavdropWidgetUpdater

    override fun onCreate() {
        super.onCreate()
        if (ENABLE_WIDGET) {
            widgetUpdater.start()
        }
    }

    companion object {
        // Widget V1 — AppWidgetProvider (WavdropWidgetProvider) is registered in
        // AndroidManifest.xml. Flip this flag to false to disable widget update calls.
        const val ENABLE_WIDGET = true
    }
}
