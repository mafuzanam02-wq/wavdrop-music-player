package com.launchpoint.wavdrop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionConfigTest {

    @Test
    fun `beta 3_1 release apk has correct version code and name`() {
        val buildFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.exists() }
        val text = buildFile.readText()

        assertTrue("versionCode must be 3 for Beta 3.1 release", "versionCode = 3" in text)
        assertEquals(
            "0.1.0-beta3.1",
            Regex("versionName\\s*=\\s*\"([^\"]+)\"")
                .find(text)
                ?.groupValues
                ?.get(1),
        )
    }
}
