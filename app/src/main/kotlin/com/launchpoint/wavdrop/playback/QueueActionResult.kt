package com.launchpoint.wavdrop.playback

/** The explicit, user-initiated queue command a [QueueActionResult] answers. */
enum class QueueAction { PlayNext, AddToQueue }

/**
 * P5-F14: the verified outcome of ONE explicit Play Next / Add to Queue command.
 *
 * "Verified" means the controller re-read its own committed LOGICAL queue after the mutation and confirmed the requested song
 * occupies the intended position; a function that merely returned proves nothing. The logical queue is the authoritative queue
 * (the physical Media3 / bounded window is reconciled to it asynchronously and is repaired, never rolled back), so a physically
 * pending edit is still [Queued]. Nothing here waits on the physical player.
 */
sealed interface QueueActionResult {

    /**
     * The song was committed to an existing queue at logical [position] (playback order). [placement] says where, which can be
     * weaker than requested: a Play Next whose current item could not be resolved is appended and reports [QueuePlacement.Appended].
     */
    data class Queued(val action: QueueAction, val placement: QueuePlacement, val position: Int) : QueueActionResult

    /** The queue was empty, so the song started a new queue and is now the current item rather than "next". */
    data object StartedQueue : QueueActionResult

    /** The command did not provably take effect. Nothing may be claimed as added. */
    data class Failed(val action: QueueAction, val reason: String) : QueueActionResult
}

enum class QueuePlacement { PlayNext, Appended }

/** Pure post-condition check behind [QueueActionResult]: trusts an intent only if the committed queue proves it. */
internal object QueueActionVerifier {
    fun verify(
        action: QueueAction,
        song: com.launchpoint.wavdrop.data.model.Song,
        sizeBefore: Int,
        committed: List<com.launchpoint.wavdrop.data.model.Song>,
        intent: QueueActionResult,
    ): QueueActionResult {
        val verified = when (intent) {
            QueueActionResult.StartedQueue -> sizeBefore == 0 && committed.size == 1 && committed[0].id == song.id
            is QueueActionResult.Queued ->
                intent.action == action && committed.size == sizeBefore + 1 && committed.getOrNull(intent.position)?.id == song.id
            is QueueActionResult.Failed -> false
        }
        return if (verified) intent else QueueActionResult.Failed(action, "not_verified")
    }
}
