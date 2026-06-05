package com.launchpoint.wavdrop.data.settings

data class AppIconAliasStateChange(
    val choice: AppIconChoice,
    val enabled: Boolean,
)

object AppIconAliasRules {
    fun switchPlan(selected: AppIconChoice): List<AppIconAliasStateChange> =
        listOf(AppIconAliasStateChange(selected, enabled = true)) +
            AppIconChoice.entries
                .filterNot { it == selected }
                .map { AppIconAliasStateChange(it, enabled = false) }
}
