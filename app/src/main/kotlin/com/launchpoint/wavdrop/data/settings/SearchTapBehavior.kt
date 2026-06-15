package com.launchpoint.wavdrop.data.settings

enum class SearchTapBehavior(val displayName: String) {
    REPLACE_QUEUE("Replace queue"),
    PRESERVE_QUEUE("Preserve queue");

    companion object {
        val DEFAULT: SearchTapBehavior = REPLACE_QUEUE

        fun fromStoredName(value: String?): SearchTapBehavior? =
            value?.let { stored -> entries.firstOrNull { it.name == stored } }

        fun fromStoredNameOrDefault(value: String?): SearchTapBehavior =
            fromStoredName(value) ?: DEFAULT
    }
}
