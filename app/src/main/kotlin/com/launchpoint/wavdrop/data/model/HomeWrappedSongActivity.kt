package com.launchpoint.wavdrop.data.model

/**
 * One row per LIVE song with PLAY/SKIP activity in the selected Home Wrapped period (WC-05). Not a Room entity: it is the result
 * projection of `TrackListenEventDao.observeHomeWrappedActivity`. [totalPlayCount] is the period's PLAY count across ALL events,
 * orphan-song events included (exactly what the full Wrapped total counted), repeated on every row so a single query result can
 * never pair a stale total with fresh per-song rows. [latestPlayAt] is the newest PLAY timestamp of the song in the period (null for
 * skip-only songs); it only exists so the old stable artist ordering can be reproduced for lowercase-equal artist keys.
 */
data class HomeWrappedSongActivity(
    val songId: Long,
    val playCount: Int,
    val skipCount: Int,
    val totalPlayCount: Int,
    val latestPlayAt: Long? = null,
)
