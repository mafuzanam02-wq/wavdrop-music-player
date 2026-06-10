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
        widgetUpdater.start()
    }
}
