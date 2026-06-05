package com.launchpoint.wavdrop.data.settings

enum class NotificationControlsSetting(val displayName: String) {
    STANDARD("Standard"),
    STANDARD_SHUFFLE("Standard + Shuffle"),
    STANDARD_REPEAT("Standard + Repeat"),
    STANDARD_SHUFFLE_REPEAT("Standard + Shuffle & Repeat"),
    ;

    val includeShuffle: Boolean
        get() = this == STANDARD_SHUFFLE || this == STANDARD_SHUFFLE_REPEAT

    val includeRepeat: Boolean
        get() = this == STANDARD_REPEAT || this == STANDARD_SHUFFLE_REPEAT
}
