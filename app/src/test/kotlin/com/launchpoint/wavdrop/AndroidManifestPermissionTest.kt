package com.launchpoint.wavdrop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `playback service remains exported only for media session service action`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val services = document.getElementsByTagName("service")
        var foundPlaybackService = false
        var hasMediaSessionAction = false
        var hasPrivateReconnectAction = false

        for (index in 0 until services.length) {
            val service = services.item(index)
            val name = service.attributes?.getNamedItem("android:name")?.nodeValue
            if (name != ".playback.PlaybackService") continue
            foundPlaybackService = true
            val exported = service.attributes?.getNamedItem("android:exported")?.nodeValue
            assertTrue("PlaybackService must stay exported for Media3", exported == "true")

            val childNodes = service.childNodes
            for (childIndex in 0 until childNodes.length) {
                val child = childNodes.item(childIndex)
                val actions = child.childNodes
                for (actionIndex in 0 until actions.length) {
                    val actionName = actions.item(actionIndex).attributes
                        ?.getNamedItem("android:name")
                        ?.nodeValue
                    if (actionName == "androidx.media3.session.MediaSessionService") {
                        hasMediaSessionAction = true
                    }
                    if (actionName == "com.launchpoint.wavdrop.ACTION_AUDIO_OUTPUT_CONNECTED") {
                        hasPrivateReconnectAction = true
                    }
                }
            }
        }

        assertTrue("PlaybackService declaration must exist", foundPlaybackService)
        assertTrue("Media3 session service action must stay declared", hasMediaSessionAction)
        assertFalse("Private reconnect action must not be exported in the service filter", hasPrivateReconnectAction)
    }
}
