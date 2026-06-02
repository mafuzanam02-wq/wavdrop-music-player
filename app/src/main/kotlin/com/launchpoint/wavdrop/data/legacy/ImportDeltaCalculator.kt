package com.launchpoint.wavdrop.data.legacy

data class ImportDelta(
    val playDelta: Int,
    val skipDelta: Int,
    val nextBaselinePlayCount: Int,
    val nextBaselineSkipCount: Int,
) {
    val hasNewStats: Boolean
        get() = playDelta > 0 || skipDelta > 0
}

object ImportDeltaCalculator {

    fun calculate(
        previousPlayCount: Int,
        previousSkipCount: Int,
        incomingPlayCount: Int,
        incomingSkipCount: Int,
    ): ImportDelta = ImportDelta(
        playDelta = maxOf(0, incomingPlayCount - previousPlayCount),
        skipDelta = maxOf(0, incomingSkipCount - previousSkipCount),
        nextBaselinePlayCount = incomingPlayCount,
        nextBaselineSkipCount = incomingSkipCount,
    )
}
