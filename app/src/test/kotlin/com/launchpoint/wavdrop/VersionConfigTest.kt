package com.launchpoint.wavdrop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionConfigTest {

    @Test
    fun `beta 7 release apk has correct version code and name`() {
        val buildFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.exists() }
        val text = buildFile.readText()

        assertTrue("versionCode must be 7 for Beta 7 release", "versionCode = 7" in text)
        assertEquals(
            "0.1.0-beta7",
            Regex("versionName\\s*=\\s*\"([^\"]+)\"")
                .find(text)
                ?.groupValues
                ?.get(1),
        )
    }
}
