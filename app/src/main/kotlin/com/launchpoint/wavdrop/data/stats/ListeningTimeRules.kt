package com.launchpoint.wavdrop.data.stats

object ListeningTimeRules {

    fun effectiveListeningTimeMs(
        playCount: Int,
        durationMs: Long,
        totalListeningTimeMs: Long,
    ): Long {
        val actualListeningTimeMs = totalListeningTimeMs.coerceAtLeast(0L)
        val estimatedListeningTimeMs = estimatedListeningTimeMs(
            playCount = playCount,
            durationMs = durationMs,
        )
        return maxOf(actualListeningTimeMs, estimatedListeningTimeMs)
    }

    private fun estimatedListeningTimeMs(
        playCount: Int,
        durationMs: Long,
    ): Long {
        if (playCount <= 0 || durationMs <= 0L) return 0L
        val plays = playCount.toLong()
        return if (plays > Long.MAX_VALUE / durationMs) {
            Long.MAX_VALUE
        } else {
            plays * durationMs
        }
    }
}
