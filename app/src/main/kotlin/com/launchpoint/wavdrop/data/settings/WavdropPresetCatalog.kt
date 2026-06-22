package com.launchpoint.wavdrop.data.settings

data class WavdropPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val bandLevels: List<Int>,
)

// Wavdrop-owned EQ preset catalog.
//
// Levels are in millibels (mB) targeting a 5-band device (S21 frequencies):
//   Band 0: 60 Hz  · Band 1: 230 Hz · Band 2: 910 Hz
//   Band 3: 3.6 kHz · Band 4: 14 kHz
//
// All values are within ±500 mB (±5 dB) — conservative, clipping-safe without preamp.
// IDs are stable lowercase-kebab strings. Never rename or reorder after shipping.
object WavdropPresetCatalog {

    val all: List<WavdropPreset> = listOf(
        WavdropPreset(
            id          = "balanced",
            displayName = "Balanced",
            description = "Clear and natural for any genre.",
            bandLevels  = listOf(100, 100, 0, 100, 100),
        ),
        WavdropPreset(
            id          = "deep-bass",
            displayName = "Deep Bass",
            description = "Extended low end and sub-bass presence.",
            bandLevels  = listOf(500, 300, -100, 0, 0),
        ),
        WavdropPreset(
            id          = "clarity",
            displayName = "Clarity",
            description = "Forward vocals and clean midrange.",
            bandLevels  = listOf(-200, -100, 200, 500, 200),
        ),
        WavdropPreset(
            id          = "warm",
            displayName = "Warm",
            description = "Smooth highs and rich low-midrange body.",
            bandLevels  = listOf(300, 200, 100, -100, -300),
        ),
        WavdropPreset(
            id          = "detail",
            displayName = "Detail",
            description = "Air, clarity, and presence.",
            bandLevels  = listOf(-100, 0, 100, 300, 500),
        ),
        WavdropPreset(
            id          = "late-night",
            displayName = "Late Night",
            description = "Balanced presence at lower volume.",
            bandLevels  = listOf(200, 100, 0, 300, 200),
        ),
        WavdropPreset(
            id          = "spoken-word",
            displayName = "Spoken Word",
            description = "Optimised for podcasts and audiobooks.",
            bandLevels  = listOf(-400, -200, 200, 500, 100),
        ),
    )

    private val byId: Map<String, WavdropPreset> = all.associateBy { it.id }

    fun findById(id: String?): WavdropPreset? = id?.let { byId[it] }
}
