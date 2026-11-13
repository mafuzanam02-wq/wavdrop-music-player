package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Intent
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-WR-VID-02C video share contract. Robolectric does not load the merged manifest, so this checks the Intent as
 * built and the FileProvider configuration as text; the real URI grant is proven on a device (instrumentation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WrappedVideoShareIntentContractTest {

    private val uri = Uri.parse("content://com.launchpoint.wavdrop.fileprovider/wrapped_video/wavdrop_wrapped_a.mp4")

    @Test fun videoShareIntentIsAReadOnlyMp4SendWithAContentUri() {
        val intent = buildWrappedVideoShareIntent(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals(uri, stream)
        assertEquals("content", stream!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("no write grant", 0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test fun theImageShareIntentIsUnchanged() {
        val png = Uri.parse("content://com.launchpoint.wavdrop.fileprovider/wrapped_share/wavdrop_wrapped_1_a.png")
        val intent = buildWrappedShareIntent(png)
        assertEquals("image/png", intent.type)
        assertEquals(png, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    private fun projectFile(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test fun fileProviderMapsOnlyTheShareDirectoryAndTheDedicatedVideoDirectory() {
        val paths = projectFile("src/main/res/xml/file_paths.xml").readText()
        val roots = Regex("<([a-z-]+-path) name=\"([^\"]+)\" path=\"([^\"]+)\"").findAll(paths).map { it.groupValues }.toList()
        assertEquals(
            "exactly the image share path and the dedicated video path",
            listOf("wrapped_share/", "wrapped_video_export/"),
            roots.map { it[3] },
        )
        assertTrue("both are cache-path (app-private)", roots.all { it[1] == "cache-path" })
        assertEquals("the video path is the one the exporter writes to", "$WRAPPED_VIDEO_DIR/", roots[1][3])
        assertFalse("the slide-export directory must never be shareable", paths.contains("wrapped_slide_export"))
        assertTrue("every mapping is a named sub-directory, never the cache root", roots.all { it[3].trim('/').isNotEmpty() && it[3] != "." })
        assertFalse(paths.contains("external-"))
        assertFalse(paths.contains("files-path"))
    }

    @Test fun providerStaysPrivateAndGrantBased() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertFalse("no storage permission was added", manifest.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }
}
