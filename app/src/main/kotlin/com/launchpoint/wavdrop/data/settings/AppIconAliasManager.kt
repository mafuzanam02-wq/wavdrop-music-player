package com.launchpoint.wavdrop.data.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AppIconAliasApplyResult(
    val selected: AppIconChoice,
    val confirmed: Boolean,
)

@Singleton
class AppIconAliasManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(choice: AppIconChoice): AppIconAliasApplyResult {
        val pm = context.packageManager

        AppIconAliasRules.switchPlan(choice).forEach { change ->
            val state = if (change.enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                change.choice.componentName(),
                state,
                PackageManager.DONT_KILL_APP,
            )
            Log.d(TAG, "setComponentEnabledSetting ${if (change.enabled) "ENABLED" else "DISABLED"} -> ${change.choice.aliasClassName}")
        }

        val selectedState = pm.getComponentEnabledSetting(choice.componentName())
        val confirmed = selectedState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (!confirmed) {
            Log.w(TAG, "Launcher alias not confirmed enabled: ${choice.aliasClassName}, state=$selectedState")
        }
        return AppIconAliasApplyResult(selected = choice, confirmed = confirmed)
    }

    private fun AppIconChoice.componentName(): ComponentName =
        ComponentName(context.packageName, aliasClassName)

    private companion object {
        const val TAG = "Wavdrop-IconSwitch"
    }
}
