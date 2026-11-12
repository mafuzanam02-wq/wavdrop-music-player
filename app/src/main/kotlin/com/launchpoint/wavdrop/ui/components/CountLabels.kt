package com.launchpoint.wavdrop.ui.components

/**
 * P5-UX-03A. English count labels with correct singular/plural wording ("1 song", "2 songs", "0 plays").
 *
 * The app's UI strings are hard-coded English (no plural resources exist), so this is a plain Kotlin formatter that can be
 * unit-tested directly. Exactly 1 selects the singular form; every other value (including 0) selects the plural. Counts in
 * this app are non-negative domain values, so negatives are not given special semantics. The number is rendered with
 * [Int.toString] by default (the format the screens used before); callers that need locale grouping pass [formattedCount].
 */
internal object CountLabels {

    fun format(
        count: Int,
        singular: String,
        plural: String = singular + "s",
        formattedCount: String = count.toString(),
    ): String = "$formattedCount ${if (count == 1) singular else plural}"

    fun songs(count: Int): String = format(count, "song", "songs")
    fun albums(count: Int): String = format(count, "album", "albums")
    fun artists(count: Int): String = format(count, "artist", "artists")
    fun plays(count: Int): String = format(count, "play", "plays")
    fun skips(count: Int): String = format(count, "skip", "skips")
}
