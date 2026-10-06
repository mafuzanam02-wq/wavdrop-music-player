package com.launchpoint.wavdrop.ui.scan

import com.launchpoint.wavdrop.data.repository.LibrarySyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presentation state of a library scan, shared by Home/Songs and Settings. Transient UI state only: never persisted.
 * [Warning] = the scan kept the existing library without changing it (EmptyPreserved); [Error] = the scan failed
 * (the repository left the existing library untouched).
 */
sealed interface LibraryScanUiState {
    data object Idle : LibraryScanUiState
    data object Scanning : LibraryScanUiState
    data object Complete : LibraryScanUiState
    data class Warning(val message: String) : LibraryScanUiState
    data class Error(val message: String) : LibraryScanUiState
}

const val LIBRARY_SCAN_FALLBACK_MESSAGE = "Library scan failed. Please try again."

/** The single definition of what each [LibrarySyncResult] means to the user. Repository-provided messages are preserved. */
fun LibrarySyncResult.toScanUiState(): LibraryScanUiState = when (this) {
    is LibrarySyncResult.Success -> LibraryScanUiState.Complete
    is LibrarySyncResult.EmptyPreserved -> LibraryScanUiState.Warning(reason)
    is LibrarySyncResult.Failed -> LibraryScanUiState.Error(reason)
}

/** Runs one scan and maps its outcome; an unexpected exception becomes [LibraryScanUiState.Error] (cancellation propagates). */
suspend fun runLibraryScan(sync: suspend () -> LibrarySyncResult): LibraryScanUiState =
    try {
        sync().toScanUiState()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LibraryScanUiState.Error(e.message ?: LIBRARY_SCAN_FALLBACK_MESSAGE)
    }

/**
 * The one scan operation owner for a screen's ViewModel: a single authoritative [state], and a start that is a no-op while a
 * scan is already running, so no entry point (initial sync, pull-to-refresh, top-bar Rescan, empty-state Rescan, Settings)
 * can launch a concurrent sync.
 */
class LibraryScanCoordinator {
    private val _state = MutableStateFlow<LibraryScanUiState>(LibraryScanUiState.Idle)
    val state: StateFlow<LibraryScanUiState> = _state.asStateFlow()

    /** Returns false (and does nothing) when a scan is already running. */
    fun start(scope: CoroutineScope, sync: suspend () -> LibrarySyncResult): Boolean {
        while (true) {
            val current = _state.value
            if (current == LibraryScanUiState.Scanning) return false
            if (_state.compareAndSet(current, LibraryScanUiState.Scanning)) break
        }
        scope.launch {
            try {
                _state.value = runLibraryScan(sync)
            } finally {
                // Cancelled mid-scan: never leave the state stuck in Scanning.
                _state.compareAndSet(LibraryScanUiState.Scanning, LibraryScanUiState.Idle)
            }
        }
        return true
    }

    /** Hides a Warning/Error (UI only; the underlying result is not changed). */
    fun dismiss() {
        val current = _state.value
        if (current is LibraryScanUiState.Warning || current is LibraryScanUiState.Error) {
            _state.compareAndSet(current, LibraryScanUiState.Idle)
        }
    }
}
