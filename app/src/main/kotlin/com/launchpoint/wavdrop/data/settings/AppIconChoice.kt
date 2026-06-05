package com.launchpoint.wavdrop.data.settings

enum class AppIconChoice(val displayName: String, val aliasClassName: String) {
    MIDNIGHT_VIOLET(
        displayName    = "Midnight Violet",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasMidnightViolet",
    ),
    CLEAN_PURPLE(
        displayName    = "Clean Purple",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasCleanPurple",
    ),
    DEEP_TEAL(
        displayName    = "Deep Teal",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasDeepTeal",
    ),
    ;

    companion object {
        fun fromStoredName(raw: String?): AppIconChoice? =
            raw?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
