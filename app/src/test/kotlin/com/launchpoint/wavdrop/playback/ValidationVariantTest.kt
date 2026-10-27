package com.launchpoint.wavdrop.playback

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationVariantTest {
    @After fun restore() { ValidationTrace.enabled = com.launchpoint.wavdrop.BuildConfig.DEBUG; ValidationTrace.sink = { android.util.Log.d(ValidationTrace.TAG, it) } }

    @Test fun committedGateStaysFalseAndOrdinaryBuildsAreFull() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        // The unit-test run is an ordinary debug build: the validation override is off unless -PwavdropValidationBounded=true.
        if (System.getProperty("wavdrop.expectValidationBounded") == null) {
            assertFalse(PhysicalQueuePolicy.VALIDATION_BOUNDED)
            assertEquals("full", PhysicalQueuePolicy.variantLabel)
        }
    }

    @Test fun thresholdIs300FullAnd301BoundedWhenOpen() {
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(300, boundedEnabled = true))
        assertEquals(QueueMode.Bounded, PhysicalQueuePolicy.modeFor(301, boundedEnabled = true))
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(301, boundedEnabled = false))
    }

    @Test fun releaseBuildConfigIsHardCodedFalseInBuildScript() {
        val script = File("build.gradle.kts").readText()
        val release = script.substringAfter("        release {").substringBefore("proguardFiles")
        assertTrue(release.contains("""buildConfigField("boolean", "WAVDROP_VALIDATION_BOUNDED", "false")"""))
        assertFalse(release.contains("validationBounded"))
        val debug = script.substringAfter("        debug {").substringBefore("        release {")
        assertTrue(debug.contains("validationBounded.toString()"))
        assertTrue(script.contains("refusing to run"))
    }

    // ── submission trace accuracy ────────────────────────────────────────────────────────────────────────────────────────

    private fun capture(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        ValidationTrace.sink = { lines += it }
        ValidationTrace.enabled = true
        block()
        return lines
    }

    private val token = PlanToken(1L, 1L)
    private val active = BoundedInstallOutcome.Active(InstalledWindow(PhysicalWindow(1L, 1L, 1000, 0, 100, wraps = false), LongArray(100), 0, 0L))

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "scripts").isDirectory && File(it, "app/src").isDirectory }

    @Test fun creatingAnOutcomeHandlerDoesNotEmitSubmission() {
        val root = repoRoot() ?: return
        val src = File(root, "app/src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        val handler = src.substringAfter("private fun boundedOutcomeHandler(").substringBefore("private fun handleBoundedOutcome(")
        assertFalse(handler.contains("ValidationTrace.windowSubmitted"))
        assertFalse(handler.contains("Submitted("))
        // Every submission report goes through an adapter-result helper; the controller never calls the raw emitter.
        assertFalse(src.contains("ValidationTrace.windowSubmitted"))
    }

    private val labeled = TracedOutcome("op") { }

    @Test fun physicalSubmissionIsAttributedAndCarriesNoBaselineCounts() {
        val lines = capture {
            ValidationTrace.physicalSubmitted(PhysicalSubmission("install", labeled), 1000)
            ValidationTrace.physicalSubmitted(PhysicalSubmission("adjust", labeled, removes = 1, inserts = 2), 1000)
            ValidationTrace.physicalSubmitted(PhysicalSubmission("install", { }), 1000)
        }
        assertEquals(
            listOf(
                "window_submitted op=op kind=install logicalSize=1000",
                "window_submitted op=op kind=adjust removes=1 inserts=2 logicalSize=1000",
                "window_submitted op=unlabeled kind=install logicalSize=1000",
            ),
            lines,
        )
    }

    @Test fun onlyABaselineRequestIsABaselineObservationNeverASubmission() {
        val lines = capture {
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Submitted("baseline"))
            ValidationTrace.baselineObservation("m", 1000, MaintenanceResult.Submitted(token, reanchor = false))
            // none of these is an observation
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Submitted("offline"))
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Submitted("empty_queue"))
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Submitted("reanchor_unverified_window"))
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Rejected("no_verifier"))
            ValidationTrace.baselineObservation("m", 1000, MutationSubmission.Failed(IllegalStateException("x")))
            ValidationTrace.baselineObservation("m", 1000, MaintenanceResult.Submitted(token, reanchor = true))
            ValidationTrace.baselineObservation("m", 1000, MaintenanceResult.NoChange)
            ValidationTrace.baselineObservation("m", 1000, MaintenanceResult.Busy)
        }
        assertEquals(List(2) { "baseline_observation op=m logicalSize=1000" }, lines)
        assertTrue(lines.none { it.startsWith("window_submitted") })
    }

    @Test fun controllerNeverEmitsSubmissionFromAResultOrAHandler() {
        val root = repoRoot() ?: return
        val src = File(root, "app/src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        assertFalse(src.contains("windowSubmitted"))
        assertFalse(src.contains("installSubmitted") || src.contains("navigationSubmitted") || src.contains("maintenanceSubmitted") || src.contains("mutationSubmitted"))
        // The single source of submission events is the adapter's observer, registered once.
        assertEquals(1, Regex("""ValidationTrace\.physicalSubmitted\(""").findAll(src).count())
    }

    @Test fun terminalEventsStayAccurateAndNeverReportSubmission() {
        var t = 0L
        ValidationTrace.clockNanos = { t }
        try {
            val lines = capture {
                t = 5_000_000L
                ValidationTrace.windowTerminal("op", 0L, active, 1000, 100)
                ValidationTrace.windowTerminal("op", 0L, BoundedInstallOutcome.Submitted(token), 1000, null)
                ValidationTrace.windowTerminal("op", 0L, BoundedInstallOutcome.Stale(token, StaleReason.Superseded), 1000, null)
                ValidationTrace.windowTerminal("op", 0L, BoundedInstallOutcome.Incomplete(token, 100, 3, "d"), 1000, null)
                ValidationTrace.windowTerminal("op", 0L, BoundedInstallOutcome.Rejected(BoundedInstallRejection.EmptyQueue), 1000, null)
                ValidationTrace.windowTerminal("op", 0L, BoundedInstallOutcome.Failed(token, IllegalStateException("x")), 1000, null)
            }
            assertEquals(5, lines.size)
            assertTrue(lines[0].startsWith("window_verified op=op elapsedMs=5 logicalSize=1000 installedCount=100 logicalCurrent=0 physicalCount=100"))
            assertTrue(lines[1].startsWith("window_not_verified op=op elapsedMs=5 result=stale"))
            assertTrue(lines[2].startsWith("window_not_verified op=op elapsedMs=5 result=incomplete expected=100 observed=3"))
            assertTrue(lines[3].startsWith("window_not_verified op=op elapsedMs=5 result=rejected"))
            assertTrue(lines[4].startsWith("window_not_verified op=op elapsedMs=5 result=failed cause=IllegalStateException"))
            assertTrue(lines.none { it.startsWith("window_submitted") })
        } finally {
            ValidationTrace.clockNanos = System::nanoTime
        }
    }

    @Test fun disabledTracingEmitsNothingAtAll() {
        val lines = capture {
            ValidationTrace.enabled = false
            ValidationTrace.buildIdentity()
            ValidationTrace.installRequested("op", 1000, QueueMode.Bounded, 0)
            ValidationTrace.physicalSubmitted(PhysicalSubmission("install", labeled), 1000)
            ValidationTrace.baselineObservation("op", 1000, MutationSubmission.Submitted("baseline"))
            ValidationTrace.baselineObservation("op", 1000, MaintenanceResult.Submitted(token, reanchor = false))
            ValidationTrace.windowTerminal("op", 0L, active, 1000, 100)
        }
        assertTrue(lines.isEmpty())
    }

    @Test fun traceIsDebugOnlyAndTheReleaseConfigurationDisablesIt() {
        val root = repoRoot() ?: return
        val src = File(root, "app/src/main/kotlin/com/launchpoint/wavdrop/playback/ValidationTrace.kt").readText()
        assertTrue(src.contains("var enabled: Boolean = BuildConfig.DEBUG"))
        // BuildConfig.DEBUG is false for the release build type by Android's definition; the override constant is hard-coded false there.
        val script = File(root, "app/build.gradle.kts").readText()
        assertTrue(script.substringAfter("        release {").substringBefore("proguardFiles").contains("\"WAVDROP_VALIDATION_BOUNDED\", \"false\""))
    }


    @Test fun captureScriptExcludesGloballySharedCrashTags() {
        val root = repoRoot() ?: return
        val script = File(root, "scripts/Capture-WavdropValidation.ps1").readText()
        val tags = script.substringAfter("\$tags = @(").substringBefore(")")
        assertFalse(tags, tags.contains("AndroidRuntime"))
        assertFalse(tags, tags.contains("-b crash") || tags.contains("crash"))
        assertTrue(tags.contains("'*:S'"))
        listOf("WavdropValidation", "WavdropStartTrace", "WavdropQueuePerf", "WavdropSessionPerf", "WavdropSessionProgress",
            "WavdropCrossfade", "WavdropNextSlot", "WavdropResume", "Wavdrop-Startup", "WavStats-PC").forEach {
            assertTrue("missing tag $it", tags.contains("'$it:"))
        }
        assertFalse(script.contains("-b crash"))
    }

    @Test fun newOutputDirectoryInsideRepositoryIsRejected() {
        val root = repoRoot() ?: return
        val ps = listOf("powershell", "pwsh").firstOrNull {
            runCatching { ProcessBuilder(it, "-NoProfile", "-Command", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
        }
        org.junit.Assume.assumeTrue("PowerShell not available", ps != null)
        val fresh = File(root, "scripts/__wavdrop_new_out_${System.nanoTime()}")
        assertFalse(fresh.exists())
        val process = ProcessBuilder(
            ps, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", File(root, "scripts/Capture-WavdropValidation.ps1").path,
            "-Label", "t", "-OutRoot", fresh.path,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 2, process.waitFor())
        assertTrue(output, output.contains("outside the Git repository"))
        assertFalse("rejected directory must not be created", fresh.exists())
    }

    // ── release safety ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun releaseOverrideStaysFalseAndReleaseTasksAreRefusedWithTheProperty() {
        val script = File("build.gradle.kts").readText()
        val release = script.substringAfter("        release {").substringBefore("proguardFiles")
        assertTrue(release.contains("""buildConfigField("boolean", "WAVDROP_VALIDATION_BOUNDED", "false")"""))
        val guard = script.substringAfter("if (validationBounded) {").substringBefore("android {")
        assertTrue(guard.contains("""name.contains("Release", ignoreCase = true)"""))
        assertTrue(guard.contains("throw GradleException"))
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
    }

    @Test fun traceCarriesNoSongIdentityAndIsSilentWhenDisabled() {
        val lines = mutableListOf<String>()
        ValidationTrace.sink = { lines += it }
        ValidationTrace.enabled = false
        ValidationTrace.installRequested("play_from_queue", 1000, QueueMode.Bounded, 5)
        assertTrue(lines.isEmpty())
        ValidationTrace.enabled = true
        ValidationTrace.installRequested("play_from_queue", 1000, QueueMode.Bounded, 5)
        assertEquals(listOf("install_requested op=play_from_queue mode=Bounded logicalSize=1000 startLogical=5"), lines)
    }
}
