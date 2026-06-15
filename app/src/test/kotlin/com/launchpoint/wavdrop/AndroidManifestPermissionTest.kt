package com.launchpoint.wavdrop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidManifestPermissionTest {

    @Test
    fun `app does not request internet permission`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val permissions = document.getElementsByTagName("uses-permission")
        val requestedPermissions = buildSet {
            for (index in 0 until permissions.length) {
                val node = permissions.item(index)
                val name = node.attributes
                    ?.getNamedItem("android:name")
                    ?.nodeValue
                    .orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }

        assertFalse("INTERNET permission must not be added", "android.permission.INTERNET" in requestedPermissions)
    }
}
