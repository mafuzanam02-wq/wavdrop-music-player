package com.launchpoint.wavdrop.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.launchpoint.wavdrop.BuildConfig

/**
 * Standard AppWidgetProvider for the Wavdrop home-screen widget.
 *
 * This class owns only widget lifecycle events. Playback state is written
 * exclusively by PlaybackService → WidgetStateStore, and RemoteViews are
 * built and pushed by WavdropWidgetUpdater.requestUpdate().
 *
 * Control button taps are handled via PendingIntent.getService() pointing
 * directly to PlaybackService; no onReceive routing is needed here.
 */
class WavdropWidgetProvider : AppWidgetProvider() {

    /** Called by the system on the update interval or when widgets are first added. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WavdropWidgetUpdater.requestUpdate(context)
    }

    /**
     * Called when the user resizes a widget instance. Re-render so the
     * correct compact/normal/tall layout is selected for the new dimensions.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (BuildConfig.DEBUG) {
            val minW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val maxH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            Log.d(TAG, "[provider] optionsChanged id=$appWidgetId minW=${minW}dp minH=${minH}dp maxW=${maxW}dp maxH=${maxH}dp")
        }
        WavdropWidgetUpdater.requestUpdate(context)
    }

    companion object {
        private const val TAG = "WavdropWidget"
    }
}
