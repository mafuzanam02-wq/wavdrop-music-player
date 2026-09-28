package com.launchpoint.wavdrop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestPermissionTest {

    @Test
    fun `Bluetooth reconnect uses connect permission without scan permission`() {
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
                permissions.item(index).attributes
                    ?.getNamedItem("android:name")
                    ?.nodeValue
                    ?.let(::add)
            }
        }

        assertTrue(
            "BLUETOOTH_CONNECT is required for Android 12+ profile broadcasts",
            "android.permission.BLUETOOTH_CONNECT" in requestedPermissions,
        )
        assertFalse(
            "Wavdrop does not scan or discover Bluetooth devices",
            "android.permission.BLUETOOTH_SCAN" in requestedPermissions,
        )
    }

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

    @Test
    fun `official Media3 media button receiver is exported for system delivery`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val receivers = document.getElementsByTagName("receiver")
        var found = false
        for (index in 0 until receivers.length) {
            val receiver = receivers.item(index)
            val name = receiver.attributes?.getNamedItem("android:name")?.nodeValue
            if (name != "androidx.media3.session.MediaButtonReceiver") continue
            found = true
            assertTrue(
                "MediaButtonReceiver must be exported for system media-button delivery",
                receiver.attributes?.getNamedItem("android:exported")?.nodeValue == "true",
            )
        }
        assertTrue("Official Media3 MediaButtonReceiver must be declared", found)
    }

    @Test
    fun `audio reconnect receiver declares classic hearing aid and LE Audio actions`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val receivers = document.getElementsByTagName("receiver")
        val actions = mutableSetOf<String>()
        for (index in 0 until receivers.length) {
            val receiver = receivers.item(index)
            val name = receiver.attributes?.getNamedItem("android:name")?.nodeValue
            if (name != ".playback.AudioOutputReconnectReceiver") continue
            val descendants = receiver.childNodes
            for (childIndex in 0 until descendants.length) {
                val actionNodes = descendants.item(childIndex).childNodes
                for (actionIndex in 0 until actionNodes.length) {
                    actionNodes.item(actionIndex).attributes
                        ?.getNamedItem("android:name")
                        ?.nodeValue
                        ?.let(actions::add)
                }
            }
        }

        assertTrue("A2DP reconnect action missing", "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" in actions)
        assertTrue("HFP reconnect action missing", "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" in actions)
        assertTrue("Hearing Aid reconnect action missing", "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED" in actions)
        assertTrue("LE Audio reconnect action missing", "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED" in actions)
    }
}
