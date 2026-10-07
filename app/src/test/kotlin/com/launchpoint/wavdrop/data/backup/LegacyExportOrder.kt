package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

/**
 * Models the row order the legacy export received from TrackListenEventDao.getAllSnapshot()
 * (SELECT * FROM track_listen_events ORDER BY occurredAt DESC): SQLite serves it by scanning the occurredAt index backwards,
 * so rows with equal occurredAt arrive in DESCENDING id order. This is NOT an assumption: it is asserted against real
 * Room/SQLite in LegacyExportTieOrderRobolectricTest. Fake-table tests use this model; real-SQL tests use the DAO itself.
 * The legacy fingerprint then applied a stable sort by WavdropBackupIntegrityV2.EVENT_ORDER to this list.
 */
internal object LegacyExportOrder {
    fun snapshot(rows: List<TrackListenEventEntity>): List<TrackListenEventEntity> =
        rows.sortedWith(compareByDescending<TrackListenEventEntity> { it.occurredAt }.thenByDescending { it.id })
}
