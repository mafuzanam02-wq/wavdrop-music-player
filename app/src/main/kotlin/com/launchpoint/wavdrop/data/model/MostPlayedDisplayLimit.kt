package com.launchpoint.wavdrop.data.model

enum class MostPlayedDisplayLimit(
    val count: Int,
    val label: String,
) {
    TOP_10(10, "Top 10"),
    TOP_25(25, "Top 25"),
    TOP_50(50, "Top 50"),
    TOP_100(100, "Top 100"),
    TOP_200(200, "Top 200"),
}
