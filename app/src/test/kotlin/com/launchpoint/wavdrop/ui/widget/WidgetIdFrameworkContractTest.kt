package com.launchpoint.wavdrop.ui.widget


import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-UI-15B2 — the id-validation assumption, exercised against Robolectric's framework model of AppWidgetManager/AppWidgetHost:
 * a widget id allocated by the host and bound to our provider is listed by `getAppWidgetIds(provider)`, a foreign provider's id
 * is not, and `WidgetConfigContract.isUsable` follows that list.
 *
 * This is a MODEL of the framework, not the real system service and not a Samsung/One UI check. The real contract evidence is the
 * AOSP source (`AppWidgetServiceImpl.bindAppWidgetId` adds the widget to `provider.widgets`, which is what
 * `getAppWidgetIds(ComponentName)` returns) and the launcher binding the id before it starts the configuration activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetIdFrameworkContractTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val ours get() = ComponentName(context, WavdropWidgetProvider::class.java)
    private val foreign get() = ComponentName("com.example.other", "com.example.other.Provider")

    // A fixed positive id, as a real launcher allocates (0 is INVALID_APPWIDGET_ID). No allocation loop: nothing here can spin.
    private val REAL_ID = 7701


    @Test fun aBoundNewWidgetIdIsListedForOurProviderAndUsableByTheConfigScreen() {
        val manager = AppWidgetManager.getInstance(context)
        val id = REAL_ID
        val info = android.appwidget.AppWidgetProviderInfo().apply { provider = ours }
        org.robolectric.Shadows.shadowOf(manager).addBoundWidget(id, info)
        val listed = manager.getAppWidgetIds(ours)
        assertTrue("a bound new id is in the provider's id list", id in listed)
        assertTrue(WidgetConfigContract.isUsable(id, listed))
    }

    @Test fun anIdBoundToAnotherProviderIsNotUsableForOurConfigScreen() {
        val manager = AppWidgetManager.getInstance(context)
        val id = REAL_ID
        org.robolectric.Shadows.shadowOf(manager).addBoundWidget(id, android.appwidget.AppWidgetProviderInfo().apply { provider = foreign })
        assertFalse(WidgetConfigContract.isUsable(id, manager.getAppWidgetIds(ours)))
    }

    @Test fun anAllocatedButUnboundIdIsNotUsable() {
        val manager = AppWidgetManager.getInstance(context)
        val id = REAL_ID
        assertFalse(WidgetConfigContract.isUsable(id, manager.getAppWidgetIds(ours)))
    }
}
