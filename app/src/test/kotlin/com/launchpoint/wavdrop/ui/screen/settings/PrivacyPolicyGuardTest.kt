package com.launchpoint.wavdrop.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WPP-1: the in-app Privacy Policy is the authority for the public page at launchpointdigital.co.za/wavdrop/privacy. This
 * guard keeps one public URL constant, proves the in-app policy references it, pins the substantive disclosures the public
 * page must mirror, and proves the app still declares no INTERNET permission. It is source-text based because
 * `WavdropAbout` is private to the About screen.
 */
class PrivacyPolicyGuardTest {

    private val url = "https://launchpointdigital.co.za/wavdrop/privacy"
    private val about = File("src/main/kotlin/com/launchpoint/wavdrop/ui/screen/settings/SettingsAboutScreen.kt").readText()
    private val policy = about.substringAfter("val PRIVACY_POLICY = listOf(").substringBefore("val TERMS_OF_USE")

    @Test fun `one authoritative public privacy URL exists and is exact`() {
        assertTrue(about.contains("""const val PRIVACY_POLICY_URL = "$url""""))
        assertEquals("the URL literal appears exactly once in the app sources", 1, Regex(Regex.escape(url)).findAll(about).count())
        val elsewhere = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "SettingsAboutScreen.kt" }
            .filter { it.readText().contains("wavdrop/privacy") }.map { it.name }.toList()
        assertTrue("the URL must not be scattered: $elsewhere", elsewhere.isEmpty())
    }

    @Test fun `the in-app privacy policy references the public URL through the constant`() {
        assertTrue(policy.contains("View the public version of this Privacy Policy: \$PRIVACY_POLICY_URL"))
    }

    @Test fun `existing privacy statements remain present`() {
        for (statement in listOf(
            "offline-first music player",
            "does not require an account",
            "does not upload your music or data to any server",
            "does not sell or share your data with advertisers or third parties",
            "Play counts, skip counts, favorite status, and total listening time per track",
            "Per-play and per-skip event records with timestamps",
            "Playlists and their track order",
            "Custom lyrics you add manually inside the app",
            "None of this data is transmitted to Wavdrop, LaunchPoint Digital, or any third party.",
            "READ_EXTERNAL_STORAGE on Android 8–12 or READ_MEDIA_AUDIO on Android 13+",
            "FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "does not request internet, location, contacts, camera, microphone, advertising ID",
            "Wavdrop does not upload this file. You are responsible for protecting your backup because it may contain personal listening data.",
            "passes a content link for that audio file to the Android share sheet",
            "the audio file is permanently removed from your device",
            "reads a .bpstat file from your device",
            "does not use advertising SDKs, analytics services, crash-reporting tools, or third-party tracking",
            "Wavdrop itself makes no network requests.",
            "Questions about this policy: info@launchpointdigital.co.za",
        )) assertTrue("missing from the in-app policy: $statement", policy.contains(statement))
        assertFalse("backups are not encrypted; the policy must not claim it", policy.contains("encrypt", ignoreCase = true))
    }

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

    @Test fun `the app still declares no INTERNET permission`() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test fun `the Bluetooth disclosure is tied to the permissions the manifest declares`() {
        assertTrue("manifest declares BLUETOOTH", "android.permission.BLUETOOTH" in declared)
        assertTrue("manifest declares BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_CONNECT" in declared)
        assertTrue("BLUETOOTH is limited to Android 11 and earlier", Regex("""android:name="android.permission.BLUETOOTH"\s+android:maxSdkVersion="30"""").containsMatchIn(manifest))
        assertFalse("no scan permission", declared.any { it.contains("BLUETOOTH_SCAN") })
        assertFalse("no advertise/admin permission", declared.any { it.contains("BLUETOOTH_ADVERTISE") || it.contains("BLUETOOTH_ADMIN") })
        // every declared Bluetooth permission is named in the policy
        for (permission in declared.filter { it.startsWith("android.permission.BLUETOOTH") }) {
            assertTrue("policy must name ${permission.substringAfterLast('.')}", policy.contains(permission.substringAfterLast('.') + " on Android") || policy.contains(" or " + permission.substringAfterLast('.') + " on Android"))
        }
        assertTrue(policy.contains("BLUETOOTH on Android 11 and earlier or BLUETOOTH_CONNECT on Android 12+"))
        assertTrue("purpose: audio connection-state handling and reconnect resume", policy.contains("Bluetooth audio connection-state handling and optional playback resume when an audio device reconnects"))
        assertTrue("no scan/discovery", policy.contains("Wavdrop does not scan for or discover nearby Bluetooth devices."))
        assertFalse("the policy must not imply pairing or tracking", Regex("pair|bluetooth identifier|bluetooth location", RegexOption.IGNORE_CASE).containsMatchIn(policy))
        assertTrue("storage/foreground disclosures remain and no-internet statement is kept",
            policy.contains("FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK") &&
                policy.contains("does not request internet, location, contacts, camera, microphone, advertising ID"))
    }
}
