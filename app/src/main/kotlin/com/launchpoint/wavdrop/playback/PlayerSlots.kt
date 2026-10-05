package com.launchpoint.wavdrop.playback

/**
 * CF-2M3: the LOGICAL role a physical player slot currently plays. Roles are data, never object-creation order: a future
 * promotion swaps which physical slot is [CURRENT] without constructing or releasing anything.
 */
internal enum class PlayerSlotRole { CURRENT, NEXT }

/** One physical player and its permanent identity. [id] never changes; the slot's [PlayerSlotRole] may. */
internal class PlayerSlot<P : Any>(val id: Int, val player: P) {
    override fun toString(): String = "PlayerSlot#$id"
}

/**
 * Pure CURRENT/NEXT role table over exactly two physical slots. It performs no playback, focus, session or lifecycle work;
 * [swapRoles] only exchanges which slot holds which role. Production never calls it in CF-2M3.
 */
internal class PlayerSlotTable<P : Any>(first: PlayerSlot<P>, second: PlayerSlot<P>) {
    init {
        require(first !== second) { "a slot table needs two distinct physical slots" }
        require(first.player !== second.player) { "two slots must wrap two distinct physical players" }
        require(first.id != second.id) { "slot ids must be distinct" }
    }

    var current: PlayerSlot<P> = first
        private set
    var next: PlayerSlot<P> = second
        private set

    fun roleOf(slot: PlayerSlot<P>): PlayerSlotRole? = when {
        slot === current -> PlayerSlotRole.CURRENT
        slot === next -> PlayerSlotRole.NEXT
        else -> null
    }

    fun swapRoles() {
        val previousCurrent = current
        current = next
        next = previousCurrent
    }

    val slots: List<PlayerSlot<P>> get() = listOf(current, next)
}
