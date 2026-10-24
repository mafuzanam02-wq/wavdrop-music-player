package com.launchpoint.wavdrop.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-A: the inactive rollout gate and the pure mode-selection rule. */
class PhysicalQueuePolicyTest {

    private val sizes = listOf(0, 1, 25, 50, 100, 299, 300, 301, 1_000, 5_000, 25_000, 50_000, Int.MAX_VALUE)

    @Test fun `the committed gate is off`() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
    }

    @Test fun `with the gate off every queue size is full`() {
        for (n in sizes) {
            assertEquals("n=$n (default)", QueueMode.Full, PhysicalQueuePolicy.modeFor(n))
            assertEquals("n=$n (explicit off)", QueueMode.Full, PhysicalQueuePolicy.modeFor(n, boundedEnabled = false))
        }
    }

    @Test fun `with the gate enabled queues of 300 or fewer stay full`() {
        for (n in listOf(0, 1, 25, 50, 100, 299, 300)) {
            assertEquals("n=$n", QueueMode.Full, PhysicalQueuePolicy.modeFor(n, boundedEnabled = true))
        }
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(-5, boundedEnabled = true))
    }

    @Test fun `with the gate enabled queues larger than 300 are bounded`() {
        for (n in listOf(301, 1_000, 5_000, 25_000, 50_000, Int.MAX_VALUE)) {
            assertEquals("n=$n", QueueMode.Bounded, PhysicalQueuePolicy.modeFor(n, boundedEnabled = true))
        }
    }

    @Test fun `the threshold is the policy constant and can be overridden only through the parameter`() {
        assertEquals(PhysicalWindowPolicy.FULL_MODE_MAX, 300)
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(1_000, boundedEnabled = true, fullModeMax = 1_000))
        assertEquals(QueueMode.Bounded, PhysicalQueuePolicy.modeFor(1_001, boundedEnabled = true, fullModeMax = 1_000))
    }

    @Test fun `the full-mode threshold keeps a ring window from holding an occurrence twice`() {
        assertTrue(PhysicalWindowPolicy.FULL_MODE_MAX >= 2 * PhysicalWindowPolicy.W_MAX)
    }

    @Test fun `mode selection depends on the size only`() {
        // Same answer for the same size every time: no hidden state, so a mode can never change mid-queue by re-evaluation.
        repeat(100) { assertEquals(QueueMode.Bounded, PhysicalQueuePolicy.modeFor(12_345, boundedEnabled = true)) }
    }

    /**
     * P5-A is dormant infrastructure: no production source file other than the three new ones may mention the new types. A
     * structural guard (source text), not a runtime check; it fails the moment a playback path starts using them, which is
     * the correct trigger for P5-B to update it deliberately.
     */
    @Test fun `no production playback path references the dormant window types yet`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: return // not run from inside the repository checkout (e.g. a sandboxed runner): nothing to inspect
        val mainDir = File(root, "app/src/main/kotlin")
        val own = setOf("PhysicalWindow.kt", "PhysicalWindowPolicy.kt", "PhysicalQueuePolicy.kt")
        val tokens = listOf(
            "PhysicalWindow", "PhysicalWindowPolicy", "PhysicalQueuePolicy", "QueueMode", "PlanToken",
            "WindowAdjustment", "NavigationStep", "AheadDecision", "HistoryDecision", "Localization",
        )
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in own }
            .filter { file ->
                val text = file.readText()
                tokens.any { Regex("""\b$it\b""").containsMatchIn(text) }
            }
            .map { it.name }
            .toList()
        assertTrue("dormant P5-A types referenced from production code: $offenders", offenders.isEmpty())
    }
}
