package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Intent
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-WR-EX-01 URI/MIME contract. Robolectric here does not load the merged manifest or resources, so this checks
 * the share Intent as built and the FileProvider configuration as text; it does NOT prove a real FileProvider URI is
 * readable by a receiving app (device check).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WrappedShareIntentContractTest {

    private val uri = Uri.parse("content://com.launchpoint.wavdrop.fileprovider/wrapped_share/wavdrop_wrapped_1_a.png")

    @Test fun shareIntentIsAReadOnlyImagePngSend() {
        val intent = buildWrappedShareIntent(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("no write grant", 0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val clip = intent.clipData
        assertNotNull("ClipData carries the grant through the chooser", clip)
        assertEquals(uri, clip!!.getItemAt(0).uri)
    }

    private fun projectFile(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test fun fileProviderConfigStillMatchesTheExportDirectoryAndStaysPrivate() {
        val paths = projectFile("src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("<cache-path"))
        assertTrue("export dir must be the only shared path", paths.contains("path=\"$WRAPPED_SHARE_DIR/\""))
        // P5-WR-VID-02C added exactly one more root (the dedicated video directory); see WrappedVideoShareIntentContractTest.
        assertEquals("image share path + dedicated video path", 2, Regex("<[a-z-]+-path ").findAll(paths).count())

        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(provider.contains("\${applicationId}.fileprovider"))
    }
}
