package com.launchpoint.wavdrop.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent

/**
 * The Android widget-configuration Activity contract, kept free of UI so it can be unit-tested:
 *  - the launcher passes the widget id as [AppWidgetManager.EXTRA_APPWIDGET_ID];
 *  - the Activity must set RESULT_CANCELED (with that id) first, so backing out cancels adding the widget;
 *  - only after a save does it return RESULT_OK with the same id.
 */
internal object WidgetConfigContract {

    fun widgetIdFrom(intent: Intent?): Int =
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

    /** An id is usable only if it is a real id that currently belongs to Wavdrop's own widget provider. */
    fun isUsable(id: Int, idsOfOurProvider: IntArray): Boolean =
        id != AppWidgetManager.INVALID_APPWIDGET_ID && id in idsOfOurProvider

    /** Data intent for the RESULT_CANCELED / RESULT_OK results: the launcher reads the id back from it. */
    fun resultData(id: Int): Intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
}
