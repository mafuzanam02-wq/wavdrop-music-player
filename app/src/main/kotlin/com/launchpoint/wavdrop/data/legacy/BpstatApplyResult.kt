package com.launchpoint.wavdrop.data.legacy

/**
 * Result returned by [com.launchpoint.wavdrop.data.repository.StatsRepository.applyBpstatImport].
 */
data class BpstatApplyResult(
    val tracksMatched: Int,
    val tracksUpdated: Int,
    val tracksSkippedNoNewStats: Int,
    val playsImported: Long,
    val skipsImported: Long,
    val unmatchedSkipped: Int,
)
