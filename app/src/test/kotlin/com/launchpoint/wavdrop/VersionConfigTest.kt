package com.launchpoint.wavdrop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionConfigTest {

    @Test
    fun `beta 10 release apk has correct version code and name`() {
        val buildFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.exists() }
        val text = buildFile.readText()

        assertTrue("versionCode must be 10 for Beta 10 release", "versionCode = 10" in text)
        assertEquals(
            "0.1.0-beta10",
            Regex("versionName\\s*=\\s*\"([^\"]+)\"")
                .find(text)
                ?.groupValues
                ?.get(1),
        )
    }

    private fun doc(name: String): String =
        listOf(File("../$name"), File(name)).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun `beta 10 release documents agree with the build version`() {
        assertTrue(doc("WHATS_NEW.md").startsWith("# What's New — Wavdrop Beta 10.0"))
        val notes = doc("RELEASE_NOTES.md")
        assertTrue("## 0.1.0-beta10" in notes)
        assertTrue("Unreleased / Engineering since beta9" !in notes)
        assertTrue("## 0.1.0-beta9" in notes) // historical entry kept
        assertTrue("`0.1.0-beta10`, versionCode 10" in doc("PROJECT_CONTEXT.md"))
        assertTrue("0.1.0-beta10 (versionCode 10)" in doc("ENGINEERING_BACKLOG_AND_DECISIONS.md"))
    }

    @Test
    fun `wave c is closed with every WC item resolved and crossfade enabled`() {
        val backlog = doc("ENGINEERING_BACKLOG_AND_DECISIONS.md")
        assertTrue("| WC-10 | **Resolved (post-Beta 10)**" in backlog)
        assertTrue("| WC-09 | **Resolved (post-Beta 10)**" in backlog)
        for (id in (1..11).map { "WC-%02d".format(it) }) {
            assertTrue("$id must be resolved", Regex("""\| $id \| \*\*Resolved""").containsMatchIn(backlog))
        }
        assertTrue("| Open (unchanged) |" !in backlog)
        val gate = File("src/main/kotlin/com/launchpoint/wavdrop/playback/CrossfadeRolloutPolicy.kt").readText()
        assertTrue("const val RUNTIME_ENABLED = true" in gate)
    }

    @Test
    fun `beta 10 notes never claim recovery from a user force-stop`() {
        val notes = doc("RELEASE_NOTES.md")
        val beta10 = notes.substringAfter("## 0.1.0-beta10").substringBefore("## 0.1.0-beta9")
        val lower = beta10.lowercase()
        assertTrue("stopped by the system or the user" !in lower)
        // Android force-stop is respected; the notes must not promise restart/resume/resurrection after it.
        val claims = Regex("(force[- ]?stop|resurrect|restart itself|relaunch itself)").findAll(lower).map { it.value }.toList()
        assertTrue("Beta 10 notes mention force-stop/resurrection: $claims", claims.isEmpty())
    }
}
