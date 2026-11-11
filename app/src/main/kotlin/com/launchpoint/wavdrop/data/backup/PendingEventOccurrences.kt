package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity

/**
 * P5-STATS-18C - occurrence-preserving identities for QUARANTINED listening events.
 *
 * ── The old failure ──────────────────────────────────────────────────────────────────────────────────────────────────────────────
 * A pending event's `originFingerprint` was `snapshotFp|songId|occurredAt|type|listenedMs` (the "base" fingerprint) and the column is
 * UNIQUE with INSERT OR IGNORE, so every further event sharing those four values (a different `eventId`, a different `durationMs`,
 * or a second identical legacy record) was silently dropped. The survivor was whichever event came FIRST in `backup.listenEvents`:
 * file order for legacy JSON (database read order, not canonical), `EVENT_ORDER` for `.wdbk`. It is NOT necessarily the canonical-first.
 *
 * ── The rule ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 * Events of one snapshot sharing a base fingerprint form a GROUP.
 *  1. Members with the same non-null `eventId` and the same `durationMs` are ONE event (a repeated record) and collapse. The same
 *     `eventId` with a DIFFERENT `durationMs` is a contradictory backup: both payloads are kept (nothing is dropped or overwritten)
 *     and the contradiction is counted (`idConflicts`).
 *  2. What remains is ordered by `(durationMs, eventId ?: "")` and occupies SLOTS 0..n-1. Slot 0 keeps the legacy base fingerprint
 *     unchanged (so every row stored before this change is already at a valid slot); slot i >= 1 is `base|n=i`.
 *  3. Only `durationMs` is persisted per row (the table has no `eventId`/`source`), so the stored multiset of a group is exactly the
 *     multiset of its members' durations. `eventId` and `source` take part ONLY in rule 1; `source` is ignored there because an
 *     event restored on another device keeps its id but gets a different source.
 *
 * ── Survivor-aware reconciliation ────────────────────────────────────────────────────────────────────────────────────────────────
 * A previously stored row may sit at slot 0 with ANY member's payload. Before inserting, the group's existing rows are read (one
 * indexed range query, only for groups of 2+ members; a singleton only needs the insert result). Each existing row ACCOUNTS FOR one
 * member with an equal payload, without being replaced or relabelled; the remaining members take the free slots in order. An existing
 * row that matches no member is an irreconcilable conflict ([PendingHistoryConflictException], the whole restore rolls back): nothing
 * is guessed. Re-running inserts nothing, because every member is then accounted for.
 *
 * Indistinguishable members (identical payload, null ids or equal durations) are interchangeable: the rows keep their MULTIPLICITY, but
 * which individual source record a given row stands for cannot be known - and is not claimed.
 */
internal object PendingEventOccurrences {

    /** One incoming occurrence. [eventId]/[source] are known only when the event comes from a backup's live event section. */
    data class Member(
        val eventType: String,
        val occurredAt: Long,
        val listenedMs: Long,
        val durationMs: Long,
        val eventId: String?,
        val source: String? = null,
    )

    /** An event plus the base fingerprint of its group. */
    class Item(val base: String, val member: Member)

    private val MEMBER_ORDER: Comparator<Member> = compareBy<Member>({ it.durationMs }, { it.eventId ?: "" })

    private val SLOT_SUFFIX = Regex("""\|n=\d+$""")

    fun slotKey(base: String, slot: Int): String = if (slot == 0) base else "$base|n=$slot"

    /** The group's base fingerprint for any stored/incoming fingerprint (strips a trailing `|n=<digits>`). */
    fun baseOf(fingerprint: String): String = SLOT_SUFFIX.replace(fingerprint, "")

    /** Inclusive lower / exclusive upper bound covering exactly the suffixed slots of [base] (`'>'` is the character after `'='`). */
    fun slotRangeLow(base: String): String = "$base|n="
    fun slotRangeHigh(base: String): String = "$base|n>"

    class Group(val members: List<Member>, val collapsedSameId: Int, val idConflicts: Int)

    /** Rule 1 and the canonical slot order. Pure and independent of the input order. */
    fun canonicalize(raw: List<Member>): Group {
        if (raw.size == 1) return Group(raw, 0, 0)
        val sorted = raw.sortedWith(MEMBER_ORDER)
        val out = ArrayList<Member>(sorted.size)
        val seen = HashSet<Pair<String, Long>>()
        val durationsById = HashMap<String, HashSet<Long>>()
        var collapsed = 0
        for (m in sorted) {
            val id = m.eventId
            if (id != null) {
                durationsById.getOrPut(id) { HashSet() } += m.durationMs
                if (!seen.add(id to m.durationMs)) { collapsed++; continue }
            }
            out += m
        }
        val conflicts = durationsById.values.sumOf { it.size - 1 }
        return Group(out, collapsed, conflicts)
    }

    class Existing(val fingerprint: String, val pendingId: Long, val event: PendingListenEventEntity)

    class ReconcilePlan(val toInsert: List<Pair<String, Member>>, val alreadyPresent: Int)

    private fun conflict(base: String): Nothing = throw PendingHistoryConflictException(
        "The quarantined history in this backup conflicts with history already stored on this device (listen event $base). Nothing was restored.",
    )

    /** Pure survivor-aware reconciliation of one group. Throws [PendingHistoryConflictException] when it cannot be done without guessing. */
    fun reconcile(base: String, parentPendingId: Long, members: List<Member>, existing: List<Existing>): ReconcilePlan {
        val first = members.first()
        for (m in members) if (m.eventType != first.eventType || m.occurredAt != first.occurredAt || m.listenedMs != first.listenedMs) conflict(base)
        val pool = members.toMutableList()
        for (e in existing.sortedBy { it.fingerprint }) {
            val ev = e.event
            if (e.pendingId != parentPendingId || ev.eventType != first.eventType || ev.occurredAt != first.occurredAt || ev.listenedMs != first.listenedMs) conflict(base)
            val at = pool.indexOfFirst { it.durationMs == ev.durationMs }
            if (at < 0) conflict(base)               // a stored row that no incoming occurrence can account for: never guess
            pool.removeAt(at)                        // accounted for, NOT replaced and NOT relabelled
        }
        val occupied = existing.mapTo(HashSet()) { it.fingerprint }
        val free = (0 until members.size).map { slotKey(base, it) }.filter { it !in occupied }
        if (free.size < pool.size) conflict(base)
        return ReconcilePlan(pool.mapIndexed { i, m -> free[i] to m }, existing.size)
    }

    /**
     * Read-only preview twin of [PendingEventWriter.write] for ONE track (P5-STATS-18C1): true when writing [items] against that track's
     * [stored] rows would insert something or would conflict (apply then reports the conflict). Compares exactly the same population
     * on both sides (this track's groups only) and by payload, not by count, so equal counts can hide neither a missing occurrence
     * nor a contradictory row.
     */
    fun needsWrite(items: List<Item>, stored: List<PendingListenEventEntity>): Boolean {
        val storedByBase = stored.groupBy { baseOf(it.originFingerprint) }
        var needed = false
        forEachGroup(items) { base, raw ->
            if (needed) return@forEachGroup
            val members = canonicalize(raw).members
            var existing = storedByBase[base].orEmpty().map { Existing(it.originFingerprint, it.pendingId, it) }
            if (members.size == 1) existing = existing.filter { it.fingerprint == base }   // mirrors the singleton path of the writer
            needed = try {
                reconcile(base, existing.firstOrNull()?.pendingId ?: 0L, members, existing).toInsert.isNotEmpty()
            } catch (_: PendingHistoryConflictException) { true }
        }
        return needed
    }

    fun forEachGroup(items: List<Item>, block: (base: String, members: List<Member>) -> Unit) {
        if (items.isEmpty()) return
        val sorted = items.sortedWith(Comparator { a, b ->
            val c = a.base.compareTo(b.base)
            if (c != 0) c else MEMBER_ORDER.compare(a.member, b.member)
        })
        var start = 0
        while (start < sorted.size) {
            var end = start + 1
            while (end < sorted.size && sorted[end].base == sorted[start].base) end++
            block(sorted[start].base, sorted.subList(start, end).map { it.member })
            start = end
        }
    }
}

/** Counts of what a write ACTUALLY persisted (never insert attempts). */
data class PendingEventWriteResult(
    val inserted: Int = 0,
    val alreadyPresent: Int = 0,
    val collapsedSameId: Int = 0,
    val idConflicts: Int = 0,
) {
    operator fun plus(o: PendingEventWriteResult) = PendingEventWriteResult(
        inserted + o.inserted, alreadyPresent + o.alreadyPresent, collapsedSameId + o.collapsedSameId, idConflicts + o.idConflicts,
    )
}

/**
 * The single place that writes quarantined events (Merge, Recovery and the 17B carried section all use it). Must run inside the caller's
 * transaction: a conflict throws and the transaction rolls back every write.
 *
 * Cost, for n events of one track: one sort O(n log n); one INSERT per event; one extra indexed range read per group of 2+ members.
 * No table is read in full and nothing is held beyond the track's own item list.
 */
class PendingEventWriter(private val dao: PendingTrackDao) {

    internal suspend fun write(pendingId: Long, items: List<PendingEventOccurrences.Item>): PendingEventWriteResult {
        var result = PendingEventWriteResult()
        val groups = ArrayList<Pair<String, List<PendingEventOccurrences.Member>>>()
        PendingEventOccurrences.forEachGroup(items) { base, members -> groups += base to members }
        for ((base, raw) in groups) {
            val group = PendingEventOccurrences.canonicalize(raw)
            val members = group.members
            var inserted = 0
            var present = 0
            if (members.size == 1) {
                val m = members.single()
                if (dao.insertEventOrIgnore(m.toEntity(pendingId, base)) != -1L) inserted = 1 else {
                    val stored = dao.getEventByFingerprint(base) ?: throw conflictFor(base)
                    val plan = PendingEventOccurrences.reconcile(base, pendingId, members, listOf(PendingEventOccurrences.Existing(base, stored.pendingId, stored)))
                    check(plan.toInsert.isEmpty())
                    present = 1
                }
            } else {
                val existing = dao.getEventsForGroup(base, PendingEventOccurrences.slotRangeLow(base), PendingEventOccurrences.slotRangeHigh(base))
                    .map { PendingEventOccurrences.Existing(it.originFingerprint, it.pendingId, it) }
                val plan = PendingEventOccurrences.reconcile(base, pendingId, members, existing)
                for ((key, m) in plan.toInsert) {
                    if (dao.insertEventOrIgnore(m.toEntity(pendingId, key)) == -1L) throw conflictFor(base)   // someone else took a free slot
                    inserted++
                }
                present = plan.alreadyPresent
            }
            result += PendingEventWriteResult(inserted, present, group.collapsedSameId, group.idConflicts)
        }
        return result
    }

    private fun conflictFor(base: String) = PendingHistoryConflictException(
        "The quarantined history in this backup conflicts with history already stored on this device (listen event $base). Nothing was restored.",
    )

    private fun PendingEventOccurrences.Member.toEntity(pendingId: Long, fingerprint: String) = PendingListenEventEntity(
        pendingId = pendingId, eventType = eventType, occurredAt = occurredAt, listenedMs = listenedMs, durationMs = durationMs,
        originFingerprint = fingerprint,
    )
}
