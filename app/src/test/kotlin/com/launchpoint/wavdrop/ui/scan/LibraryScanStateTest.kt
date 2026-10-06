package com.launchpoint.wavdrop.ui.scan

import com.launchpoint.wavdrop.data.repository.LibrarySyncResult
import com.launchpoint.wavdrop.ui.permission.AudioPermissionCopy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanStateTest {

    private suspend fun LibraryScanCoordinator.awaitScanning(scanning: Boolean) =
        withTimeout(5_000) { state.first { (it == LibraryScanUiState.Scanning) == scanning } }

    private suspend fun LibraryScanCoordinator.await(predicate: (LibraryScanUiState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    // ---- shared mapping ----
    @Test fun `mapping keeps the repository messages`() {
        assertEquals(LibraryScanUiState.Complete, LibrarySyncResult.Success(12).toScanUiState())
        assertEquals(LibraryScanUiState.Warning("kept"), LibrarySyncResult.EmptyPreserved("kept").toScanUiState())
        assertEquals(LibraryScanUiState.Error("nope"), LibrarySyncResult.Failed("nope").toScanUiState())
    }

    @Test fun `an unexpected exception becomes an error and cancellation propagates`() = runBlocking {
        assertEquals(LibraryScanUiState.Error("boom"), runLibraryScan { throw IllegalStateException("boom") })
        assertEquals(LibraryScanUiState.Error(LIBRARY_SCAN_FALLBACK_MESSAGE), runLibraryScan { throw IllegalStateException() })
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val started = CompletableDeferred<Unit>()
        val result = scope.async {
            runLibraryScan {
                started.complete(Unit)
                CompletableDeferred<LibrarySyncResult>().await()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    // ---- coordinator ----
    @Test fun `scan success ends Complete`() = runBlocking {
        val c = LibraryScanCoordinator()
        val scope = CoroutineScope(Dispatchers.Default)
        assertEquals(LibraryScanUiState.Idle, c.state.value)
        assertTrue(c.start(scope) { LibrarySyncResult.Success(3) })
        assertEquals(LibraryScanUiState.Complete, c.await { it == LibraryScanUiState.Complete })
    }

    @Test fun `scan failed and empty preserved surface as error and warning`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val failed = LibraryScanCoordinator()
        failed.start(scope) { LibrarySyncResult.Failed("could not complete") }
        assertEquals(LibraryScanUiState.Error("could not complete"), failed.await { it is LibraryScanUiState.Error })
        val preserved = LibraryScanCoordinator()
        preserved.start(scope) { LibrarySyncResult.EmptyPreserved("no music found; kept") }
        assertEquals(LibraryScanUiState.Warning("no music found; kept"), preserved.await { it is LibraryScanUiState.Warning })
    }

    @Test fun `Scanning is entered and left and no concurrent scan starts`() = runBlocking {
        val c = LibraryScanCoordinator()
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = CompletableDeferred<LibrarySyncResult>()
        val calls = AtomicInteger()
        assertTrue(c.start(scope) { calls.incrementAndGet(); gate.await() })
        assertEquals(LibraryScanUiState.Scanning, c.state.value)
        assertFalse(c.start(scope) { calls.incrementAndGet(); LibrarySyncResult.Success(1) })
        assertFalse(c.start(scope) { calls.incrementAndGet(); LibrarySyncResult.Success(1) })
        gate.complete(LibrarySyncResult.Success(1))
        c.awaitScanning(false)
        assertEquals(1, calls.get())
        assertEquals(LibraryScanUiState.Complete, c.state.value)
    }

    @Test fun `a successful retry clears the earlier error and warning`() = runBlocking {
        val c = LibraryScanCoordinator()
        val scope = CoroutineScope(Dispatchers.Default)
        c.start(scope) { LibrarySyncResult.Failed("x") }
        c.await { it is LibraryScanUiState.Error }
        assertTrue(c.start(scope) { LibrarySyncResult.Success(2) })
        assertEquals(LibraryScanUiState.Complete, c.await { it == LibraryScanUiState.Complete })
        c.start(scope) { LibrarySyncResult.EmptyPreserved("w") }
        c.await { it is LibraryScanUiState.Warning }
        c.start(scope) { LibrarySyncResult.Success(2) }
        assertEquals(LibraryScanUiState.Complete, c.await { it == LibraryScanUiState.Complete })
    }

    @Test fun `dismiss hides only a warning or error`() = runBlocking {
        val c = LibraryScanCoordinator()
        val scope = CoroutineScope(Dispatchers.Default)
        c.start(scope) { LibrarySyncResult.Failed("x") }
        c.await { it is LibraryScanUiState.Error }
        c.dismiss()
        assertEquals(LibraryScanUiState.Idle, c.state.value)
        c.start(scope) { LibrarySyncResult.Success(1) }
        c.await { it == LibraryScanUiState.Complete }
        c.dismiss()
        assertEquals(LibraryScanUiState.Complete, c.state.value)
    }

    @Test fun `a cancelled scan never leaves the state stuck in Scanning`() = runBlocking {
        val c = LibraryScanCoordinator()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val started = CompletableDeferred<Unit>()
        c.start(scope) { started.complete(Unit); CompletableDeferred<LibrarySyncResult>().await() }
        started.await()
        job.cancelAndJoin()
        assertEquals(LibraryScanUiState.Idle, c.state.value)
    }

    // ---- wiring guards (no Compose test dependency in this project) ----
    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()

    @Test fun `home owns one scan operation and every entry point uses it`() {
        val vm = src("ui/screen/home/HomeViewModel.kt")
        assertEquals(1, Regex("""repository\.sync\(\)""").findAll(vm).count())
        assertTrue("libraryScan.start(viewModelScope) { repository.sync() }" in vm)
        assertFalse("fun refreshLibrary" in vm)
        assertTrue("val isRefreshing: StateFlow<Boolean> = scanState" in vm) // derived, never contradicts scanState
        val home = src("ui/screen/home/HomeScreen.kt")
        assertTrue("onRefresh    = viewModel::rescanLibrary" in home) // routine Home rescan is pull-to-refresh, same authority as Songs
        assertTrue("PullToRefreshBox(" in home && "isRefreshing = scanState == LibraryScanUiState.Scanning" in home) // scan state is the only refresh state
        assertTrue("onRescan               = viewModel::rescanLibrary" in home) // dashboard / empty state
        assertTrue("onRetry           = viewModel::rescanLibrary" in home)
        assertFalse("no Home top-bar overflow / Rescan menu", "HomeOverflowMenu" in home || "\"More options\"" in home || "MoreVert" in home || "DropdownMenu" in home)
        assertTrue("the contextual empty-state Rescan button stays", "\"Rescan library\"" in home)
        assertTrue("explicit scan recovery stays", "LibraryScanStatus(" in home && "onDismiss         = viewModel::dismissScanMessage" in home)
        assertTrue("onRefresh    = viewModel::rescanLibrary" in src("ui/screen/songs/SongsScreen.kt"))
    }

    @Test fun `settings uses the same shared mapping and coordinator`() {
        val s = src("ui/screen/settings/SettingsViewModel.kt")
        assertTrue("LibraryScanCoordinator()" in s)
        assertFalse("sealed interface LibraryScanUiState" in s)
        assertFalse("LibrarySyncResult.Failed" in s)
    }

    @Test fun `copy stays distinct`() {
        assertEquals("Allow music access", AudioPermissionCopy.FIRST_RUN_TITLE)
        assertEquals("Music access was turned off", AudioPermissionCopy.REVOKED_TITLE)
        assertEquals("Music access blocked", AudioPermissionCopy.BLOCKED_TITLE)
        assertTrue("existing library data has been kept" in AudioPermissionCopy.REVOKED_BODY)
        assertEquals("Try again", LibraryScanCopy.RETRY)
        assertEquals("Library scan couldn't complete", LibraryScanCopy.ERROR_TITLE)
        val gate = src("ui/permission/AudioPermissionGate.kt")
        assertTrue("AudioPermissionStatus.Revoked -> AudioPermissionRevokedContent" in gate)
        assertTrue("AudioPermissionResolver.resolve(" in gate)
    }
}
