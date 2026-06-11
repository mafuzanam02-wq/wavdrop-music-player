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
    OBSIDIAN_BLACK(
        displayName    = "Obsidian Black",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasObsidianBlack",
    ),
    OCEAN_BLUE(
        displayName    = "Ocean Blue",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasOceanBlue",
    ),
    SUNSET_ORANGE(
        displayName    = "Sunset Orange",
        aliasClassName = "com.launchpoint.wavdrop.MainActivityAliasSunsetOrange",
    ),
    ;

    companion object {
        /**
         * Fresh-install launcher icon. Must match the single activity-alias with
         * android:enabled="true" in AndroidManifest.xml.
         */
        val DEFAULT = OBSIDIAN_BLACK

        fun fromStoredName(raw: String?): AppIconChoice? =
            raw?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
