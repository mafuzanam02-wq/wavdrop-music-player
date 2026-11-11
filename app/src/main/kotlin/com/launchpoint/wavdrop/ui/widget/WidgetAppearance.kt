package com.launchpoint.wavdrop.ui.widget

import com.launchpoint.wavdrop.R

/** How the widget card is painted. ARTWORK fills the card with the album art (default); SOLID never shows it as a background. */
enum class WidgetBackgroundStyle(val key: String) {
    ARTWORK("artwork"),
    SOLID("solid");

    companion object {
        /** Unknown or missing values fall back to the default so a widget never ends up in an undefined style. */
        fun fromKey(key: String?): WidgetBackgroundStyle = entries.firstOrNull { it.key == key } ?: ARTWORK
    }
}

/** How strongly the dark scrim covers the artwork background. Only meaningful for [WidgetBackgroundStyle.ARTWORK]. */
enum class WidgetArtworkIntensity(val key: String, val scrimColorRes: Int) {
    LIGHT("light", R.color.widget_scrim_light),
    BALANCED("balanced", R.color.widget_scrim_balanced),
    DARK("dark", R.color.widget_scrim_dark);

    companion object {
        fun fromKey(key: String?): WidgetArtworkIntensity = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/**
 * One widget instance's appearance. The default (Artwork + Balanced) is exactly how the widget looked before customization existed,
 * so widgets placed earlier keep their look without being touched.
 */
data class WidgetAppearance(
    val style: WidgetBackgroundStyle = WidgetBackgroundStyle.ARTWORK,
    val intensity: WidgetArtworkIntensity = WidgetArtworkIntensity.BALANCED,
) {
    /** The intensity choice only applies to the Artwork style; the configuration screen hides it for Solid. */
    val intensityApplies: Boolean get() = style == WidgetBackgroundStyle.ARTWORK

    /** What rendering actually uses: Solid ignores the stored intensity entirely. */
    fun effective(): WidgetAppearance =
        if (style == WidgetBackgroundStyle.SOLID) WidgetAppearance(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.BALANCED) else this

    companion object {
        val DEFAULT = WidgetAppearance()
    }
}
