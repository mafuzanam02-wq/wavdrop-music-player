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

    @Test
    fun `playback service declares media library and browser actions without lifecycle machinery`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)

        val actions = buildSet {
            val services = document.getElementsByTagName("service")
            for (index in 0 until services.length) {
                val service = services.item(index)
                if (service.attributes?.getNamedItem("android:name")?.nodeValue != ".playback.PlaybackService") continue
                val filters = service.childNodes
                for (i in 0 until filters.length) {
                    val actionNodes = filters.item(i).childNodes
                    for (j in 0 until actionNodes.length) {
                        actionNodes.item(j).attributes?.getNamedItem("android:name")?.nodeValue?.let(::add)
                    }
                }
            }
        }
        assertTrue("androidx.media3.session.MediaLibraryService" in actions)
        assertTrue("android.media.browse.MediaBrowserService" in actions)

        // Strip XML comments so explanatory text cannot trip the lifecycle-machinery check.
        val text = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        listOf("BOOT_COMPLETED", "AppKilledReceiver", "AlarmManager", "stopWithTask").forEach {
            assertFalse("$it must not appear in the manifest", text.contains(it))
        }
        assertTrue("No second playback service", document.getElementsByTagName("service").length == 1)
    }

    @Test
    fun `every launcher alias has separate modern and legacy music filters with enablement preserved`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)

        val aliases = document.getElementsByTagName("activity-alias")
        assertTrue("Expected the six launcher aliases", aliases.length == 6)
        val enabledByName = mutableMapOf<String, String?>()

        for (index in 0 until aliases.length) {
            val alias = aliases.item(index)
            val name = alias.attributes.getNamedItem("android:name").nodeValue
            assertTrue(
                "$name must target MainActivity",
                alias.attributes.getNamedItem("android:targetActivity")?.nodeValue == ".MainActivity",
            )
            enabledByName[name] = alias.attributes.getNamedItem("android:enabled")?.nodeValue

            // Each intent-filter as (actions, categories).
            val filters = mutableListOf<Pair<Set<String>, Set<String>>>()
            val children = alias.childNodes
            for (i in 0 until children.length) {
                val filter = children.item(i)
                if (filter.nodeName != "intent-filter") continue
                val actions = mutableSetOf<String>()
                val categories = mutableSetOf<String>()
                val parts = filter.childNodes
                for (j in 0 until parts.length) {
                    val part = parts.item(j)
                    val value = part.attributes?.getNamedItem("android:name")?.nodeValue ?: continue
                    if (part.nodeName == "action") actions += value
                    if (part.nodeName == "category") categories += value
                }
                filters += actions to categories
            }

            val modern = filters.filter { "android.intent.action.MAIN" in it.first }
            assertTrue("$name needs exactly one MAIN filter", modern.size == 1)
            val modernCategories = modern.single().second
            listOf("DEFAULT", "LAUNCHER", "APP_MUSIC").forEach {
                assertTrue("$name modern filter needs $it", "android.intent.category.$it" in modernCategories)
            }
            assertFalse(
                "$name must not merge MUSIC_PLAYER into the launcher filter",
                "android.intent.action.MUSIC_PLAYER" in modern.single().first,
            )

            val legacy = filters.filter { "android.intent.action.MUSIC_PLAYER" in it.first }
            assertTrue("$name needs exactly one MUSIC_PLAYER filter", legacy.size == 1)
            assertTrue(
                "$name legacy filter is only MUSIC_PLAYER + DEFAULT",
                legacy.single().first == setOf("android.intent.action.MUSIC_PLAYER") &&
                    legacy.single().second == setOf("android.intent.category.DEFAULT"),
            )
        }

        assertTrue(enabledByName[".MainActivityAliasObsidianBlack"] == "true")
        listOf(
            ".MainActivityAliasMidnightViolet",
            ".MainActivityAliasCleanPurple",
            ".MainActivityAliasDeepTeal",
            ".MainActivityAliasOceanBlue",
            ".MainActivityAliasSunsetOrange",
        ).forEach { assertTrue("$it stays disabled", enabledByName[it] == "false") }

        // MainActivity itself must not gain the legacy action.
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val actionNodes = (activities.item(index) as org.w3c.dom.Element).getElementsByTagName("action")
            for (i in 0 until actionNodes.length) {
                assertFalse(
                    actionNodes.item(i).attributes.getNamedItem("android:name").nodeValue ==
                        "android.intent.action.MUSIC_PLAYER",
                )
            }
        }
    }
}
