package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-WR-EX-01: the pure share pipeline (gate, orchestration, file naming/writing, retention, rectangle validation).
 * These tests use fakes for capture/encode/launch; they do NOT establish real PixelCopy, FileProvider or chooser
 * behaviour on a device (see WrappedShareIntentContractTest for the Android intent contract).
 */
class WrappedShareExportTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("wrapped_share_test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private class Fake {
        var captures = 0
        var launches = 0
        val recycled = mutableListOf<Any>()
        val deleted = mutableListOf<File>()
        val unexpectedStages = mutableListOf<String>()
    }

    private suspend fun run(
        gate: WrappedShareGate,
        f: Fake,
        isCurrent: () -> Boolean = { true },
        capture: suspend () -> String? = { f.captures++; "bitmap" },
        encode: suspend (String) -> File? = { File(dir, "x.png") },
        launch: (File) -> Boolean = { f.launches++; true },
    ) = runWrappedShare(
        gate = gate, isCurrent = isCurrent, capture = capture, encode = encode,
        recycle = { f.recycled += it }, launch = launch, deleteFile = { f.deleted += it },
        onUnexpected = { stage, _ -> f.unexpectedStages += stage },
    )

    // 1 + 2: single in-flight, released on success
    @Test fun onlyOneExportIsActiveAndTheGateIsReleasedOnSuccess() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val hold = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            run(gate, f, capture = { f.captures++; hold.await(); "bitmap" })
        }
        assertTrue(gate.isActive)
        assertEquals(WrappedShareOutcome.AlreadyRunning, run(gate, f)) // overlapping tap is ignored
        assertEquals("second request must not start a capture", 1, f.captures)
        hold.complete(Unit)
        assertEquals(WrappedShareOutcome.Shared, first.await())
        assertFalse(gate.isActive)
        assertEquals(1, f.launches)
        assertEquals(listOf<Any>("bitmap"), f.recycled)
        assertEquals(WrappedShareOutcome.Shared, run(gate, f)) // usable again afterwards
    }

    // 3: capture failure releases the gate
    @Test fun captureFailureReleasesTheGateAndDoesNotLaunch() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        assertEquals(WrappedShareOutcome.CaptureFailed, run(gate, f, capture = { null }))
        assertFalse(gate.isActive)
        assertEquals(0, f.launches)
        assertEquals(WrappedShareOutcome.Shared, run(gate, f))
    }

    // 4: encoding failure does not launch; bitmap released
    @Test fun encodeFailureDoesNotLaunchAChooserAndReleasesBitmapAndGate() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        assertEquals(WrappedShareOutcome.EncodeFailed, run(gate, f, encode = { null }))
        assertEquals(0, f.launches)
        assertEquals(1, f.recycled.size)
        assertFalse(gate.isActive)
    }

    // Correction 01: unexpected exceptions in a stage are recoverable results, not crashes.
    @Test fun unexpectedCaptureExceptionIsRecoverableAndAllowsAnotherExport() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val out = run(gate, f, capture = { throw IllegalStateException("capture boom") })
        assertEquals(WrappedShareOutcome.CaptureFailed, out)
        assertEquals(0, f.launches)
        assertFalse(gate.isActive)
        assertEquals(listOf("capture"), f.unexpectedStages)
        assertEquals(WrappedShareOutcome.Shared, run(gate, f)) // UI can initiate another export
        assertEquals(1, f.launches)
    }

    @Test fun unexpectedEncodeExceptionCannotLaunchTheChooserAndReleasesEverything() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val out = run(gate, f, encode = { throw IOException("write boom") })
        assertEquals(WrappedShareOutcome.EncodeFailed, out)
        assertEquals(0, f.launches)
        assertEquals(1, f.recycled.size)
        assertFalse(gate.isActive)
        assertEquals(listOf("encode"), f.unexpectedStages)
        assertEquals(WrappedShareOutcome.Shared, run(gate, f))
    }

    @Test fun chooserLaunchExceptionIsHandledDeletesTheExportAndReleasesTheGate() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val out = run(gate, f, launch = { throw SecurityException("grant boom") })
        assertEquals(WrappedShareOutcome.LaunchFailed, out)
        assertEquals(listOf(File(dir, "x.png")), f.deleted)
        assertFalse(gate.isActive)
        assertEquals(listOf("launch"), f.unexpectedStages)
        assertEquals(WrappedShareOutcome.Shared, run(gate, f))
    }

    @Test fun cancellationExceptionFromAStageIsRethrownNotConvertedAndNeverLaunches() {
        val gate = WrappedShareGate()
        val f = Fake()
        var thrown: Throwable? = null
        try {
            runBlocking { run(gate, f, encode = { throw kotlinx.coroutines.CancellationException("cancelled") }) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }
        assertNotNull("cancellation must propagate", thrown)
        assertFalse(gate.isActive)
        assertEquals(1, f.recycled.size)
        assertEquals(0, f.launches)
        assertTrue("cancellation is not reported as an unexpected failure", f.unexpectedStages.isEmpty())
    }

    @Test fun securityExceptionWhileWritingIsRecoverableAndLeavesNoPartial() {
        assertNull(writeWrappedSharePng(dir, 1L, "a") { out -> out.write(1); throw SecurityException("denied") })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun launchFailureDeletesTheExportAndReleasesTheGate() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        assertEquals(WrappedShareOutcome.LaunchFailed, run(gate, f, launch = { false }))
        assertEquals(listOf(File(dir, "x.png")), f.deleted)
        assertFalse(gate.isActive)
    }

    // 9: stale / lifecycle invalidation
    @Test fun staleAfterCaptureSkipsEncodingAndLaunch() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        var encoded = false
        val out = run(gate, f, isCurrent = { false }, encode = { encoded = true; File(dir, "x.png") })
        assertEquals(WrappedShareOutcome.Stale, out)
        assertFalse(encoded)
        assertEquals(0, f.launches)
        assertEquals(1, f.recycled.size)
        assertFalse(gate.isActive)
    }

    @Test fun staleAfterEncodeDeletesTheFileAndDoesNotLaunch() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        var checks = 0
        val out = run(gate, f, isCurrent = { ++checks < 2 }) // valid before encode, invalid before launch
        assertEquals(WrappedShareOutcome.Stale, out)
        assertEquals(0, f.launches)
        assertEquals(listOf(File(dir, "x.png")), f.deleted)
        assertFalse(gate.isActive)
    }

    @Test fun cancellationDuringCaptureReleasesTheGateAndLaunchesNothing() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val never = CompletableDeferred<String?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { run(gate, f, capture = { never.await() }) }
        assertTrue(gate.isActive)
        job.cancelAndJoin()
        assertFalse(gate.isActive)
        assertEquals(0, f.launches)
        assertTrue("a bitmap the platform may still write to is not recycled", f.recycled.isEmpty())
    }

    @Test fun cancellationDuringEncodeRecyclesAfterwardsReleasesTheGateAndLaunchesNothing() = runBlocking {
        val gate = WrappedShareGate()
        val f = Fake()
        val never = CompletableDeferred<File?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { run(gate, f, encode = { never.await() }) }
        job.cancelAndJoin()
        assertFalse(gate.isActive)
        assertEquals(0, f.launches)
        assertEquals(1, f.recycled.size)
    }

    // 5: partial files cleaned after failure
    @Test fun failedCompressLeavesNoFile() {
        assertNull(writeWrappedSharePng(dir, 1L, "a") { out -> out.write(byteArrayOf(1, 2, 3)); false })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun ioErrorDuringCompressLeavesNoFile() {
        assertNull(writeWrappedSharePng(dir, 1L, "a") { out -> out.write(1); throw IOException("disk full") })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun emptyOutputIsRejectedAndLeavesNoFile() {
        assertNull(writeWrappedSharePng(dir, 1L, "a") { true })
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun successfulWriteProducesOnlyTheFinalPng() {
        val f = writeWrappedSharePng(dir, 5L, "n") { out -> out.write(byteArrayOf(9, 8, 7)); true }
        assertNotNull(f)
        assertEquals(listOf(f!!.name), dir.listFiles()!!.map { it.name })
        assertEquals(wrappedShareFileName(5L, "n"), f.name)
        assertEquals(listOf<Byte>(9, 8, 7), f.readBytes().toList())
    }

    // 6: distinct exports never overwrite one another
    @Test fun sameMillisecondExportsGetDistinctFilesWithIntactContent() {
        val a = writeWrappedSharePng(dir, 100L) { it.write(byteArrayOf(1)); true }!!
        val b = writeWrappedSharePng(dir, 100L) { it.write(byteArrayOf(2)); true }!!
        assertNotEquals(a.name, b.name)
        assertEquals(listOf<Byte>(1), a.readBytes().toList()) // earlier export still intact
        assertEquals(listOf<Byte>(2), b.readBytes().toList())
        assertTrue(a.name.startsWith(WRAPPED_SHARE_PREFIX) && a.name.endsWith(".png"))
    }

    // 7: retention
    @Test fun retentionOnlyRemovesOldOwnedFilesAndKeepsRecentOnes() {
        val now = 10L * WRAPPED_SHARE_RETENTION_MS
        fun file(name: String, ageMs: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1)); setLastModified(now - ageMs)
        }
        val old = file(wrappedShareFileName(1, "old"), WRAPPED_SHARE_RETENTION_MS + 1_000)
        val oldPart = file(wrappedShareFileName(2, "p") + ".part", WRAPPED_SHARE_RETENTION_MS + 1_000)
        val legacyOld = file("wavdrop_wrapped.png", WRAPPED_SHARE_RETENTION_MS + 1_000)
        val recent = file(wrappedShareFileName(3, "recent"), WRAPPED_SHARE_RETENTION_MS - 60_000)
        val justNow = file(wrappedShareFileName(4, "now"), 0)
        val foreign = file("something_else.png", WRAPPED_SHARE_RETENTION_MS * 5)

        assertEquals(3, pruneWrappedShareDir(dir, now))
        assertFalse(old.exists()); assertFalse(oldPart.exists()); assertFalse(legacyOld.exists())
        assertTrue("an export still within the retention window may be read by a receiver", recent.exists())
        assertTrue(justNow.exists())
        assertTrue("files we do not own are never touched", foreign.exists())
    }

    @Test fun pruneToleratesAMissingDirectory() {
        assertEquals(0, pruneWrappedShareDir(File(dir, "nope"), 1L))
    }

    // 10: invalid capture dimensions
    @Test fun captureRectValidation() {
        assertTrue(isCaptureRectValid(0, 0, 100, 200, 1080, 2340))
        assertTrue(isCaptureRectValid(10, 20, 1080, 2340, 1080, 2340))
        assertFalse("empty width", isCaptureRectValid(50, 0, 50, 10, 1080, 2340))
        assertFalse("empty height", isCaptureRectValid(0, 50, 10, 50, 1080, 2340))
        assertFalse("inverted", isCaptureRectValid(100, 100, 50, 50, 1080, 2340))
        assertFalse("negative origin", isCaptureRectValid(-1, 0, 100, 100, 1080, 2340))
        assertFalse("beyond right edge", isCaptureRectValid(0, 0, 1081, 100, 1080, 2340))
        assertFalse("beyond bottom edge", isCaptureRectValid(0, 0, 100, 2341, 1080, 2340))
        assertFalse("window not laid out", isCaptureRectValid(0, 0, 100, 100, 0, 0))
    }

    @Test fun gateAcquireIsExclusiveAndReusable() {
        val gate = WrappedShareGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
