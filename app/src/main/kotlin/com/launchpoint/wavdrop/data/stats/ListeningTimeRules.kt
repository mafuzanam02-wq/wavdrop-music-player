package com.launchpoint.wavdrop.data.stats

object ListeningTimeRules {

    fun effectiveListeningTimeMs(
        playCount: Int,
        durationMs: Long,
        totalListeningTimeMs: Long,
    ): Long {
        if (totalListeningTimeMs > 0L) return totalListeningTimeMs
        if (playCount <= 0 || durationMs <= 0L) return 0L

        val plays = playCount.toLong()
        return if (plays > Long.MAX_VALUE / durationMs) {
            Long.MAX_VALUE
        } else {
            plays * durationMs
        }
    }
}
