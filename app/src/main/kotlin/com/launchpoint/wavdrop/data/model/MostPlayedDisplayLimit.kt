package com.launchpoint.wavdrop.data.model

enum class MostPlayedDisplayLimit(
    val count: Int,
    val label: String,
) {
    TOP_10(10, "Top 10"),
    TOP_25(25, "Top 25"),
    TOP_50(50, "Top 50"),
}
