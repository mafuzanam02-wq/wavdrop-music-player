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
        // Widget v1 is kept dormant for a future release. Flipping this to true
        // also requires restoring the WavdropWidgetReceiver entry in
        // AndroidManifest.xml so the widget appears in the launcher picker.
        const val ENABLE_WIDGET = false
    }
}
