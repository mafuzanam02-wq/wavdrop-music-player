package com.launchpoint.wavdrop.ui.screen.nowplaying

import com.launchpoint.wavdrop.data.model.ExternalAudioIdentity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.QueueSaveResult

/** QSP-1 copy. One place, so the dialog, the snackbar and the tests agree. */
internal const val QUEUE_SAVE_MENU_LABEL = "Save queue as playlist"
internal const val QUEUE_SAVED_MESSAGE = "Queue saved as playlist"
internal const val QUEUE_SAVE_BLANK_ERROR = "Enter a playlist name"
internal const val QUEUE_SAVE_DUPLICATE_ERROR = "A playlist with this name already exists"
internal const val QUEUE_SAVE_EMPTY_ERROR = "The queue is empty"
internal const val QUEUE_SAVE_UNSAVABLE_ERROR = "This queue can't be saved as a playlist"

/**
 * The queue-level save action is offered only for a non-empty queue made of ordinary WavDrop library songs. An external
 * ACTION_VIEW file is not a library song, so a queue that contains one is not saveable.
 */
internal fun canSaveQueue(queue: List<Song>): Boolean =
    queue.isNotEmpty() && queue.none(ExternalAudioIdentity::isExternalAudio)

/**
 * The song ids of [queue] in its exact current order, one per occurrence (a repeated song stays repeated), as a NEW immutable
 * list. This is the only thing the repository receives; it never sees UI Song objects or playback indices.
 */
internal fun queueSongIdsSnapshot(queue: List<Song>): List<Long> = queue.map { it.id }

/** What the dialog does with a repository result: close and confirm, or stay open with an inline error. */
internal sealed interface QueueSaveOutcome {
    data object Saved : QueueSaveOutcome
    data class Error(val message: String) : QueueSaveOutcome
}

internal fun queueSaveOutcome(result: QueueSaveResult): QueueSaveOutcome = when (result) {
    is QueueSaveResult.Success -> QueueSaveOutcome.Saved
    QueueSaveResult.BlankName -> QueueSaveOutcome.Error(QUEUE_SAVE_BLANK_ERROR)
    QueueSaveResult.DuplicateName -> QueueSaveOutcome.Error(QUEUE_SAVE_DUPLICATE_ERROR)
    QueueSaveResult.EmptyQueue -> QueueSaveOutcome.Error(QUEUE_SAVE_EMPTY_ERROR)
    QueueSaveResult.UnsavableQueue -> QueueSaveOutcome.Error(QUEUE_SAVE_UNSAVABLE_ERROR)
}
