package com.launchpoint.wavdrop.data.settings

/**
 * Curated Wrapped visual styles (WU-07). One presentation contract, three looks — data, order,
 * calculations, and navigation are identical across all three; only the surface/depth/emphasis
 * treatment changes (see `WrappedStyleTokens`).
 */
enum class WrappedVisualStyle(val displayName: String, val description: String) {
    GLASS_FLOW(
        displayName = "Glass Flow",
        description = "Translucent frosted cards with soft depth and artwork-driven ambience.",
    ),
    STUDIO_CARDS(
        displayName = "Studio Cards",
        description = "Editorial, mostly-opaque cards with strong typography and clean separators.",
    ),
    NIGHT_PULSE(
        displayName = "Night Pulse",
        description = "Deeper contrast and larger artwork presence with restrained accent emphasis.",
    );

    companion object {
        val DEFAULT: WrappedVisualStyle = GLASS_FLOW

        /** Safe parse for a persisted value: unknown/future/null falls back to [DEFAULT]. */
        fun fromStorage(raw: String?): WrappedVisualStyle =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
