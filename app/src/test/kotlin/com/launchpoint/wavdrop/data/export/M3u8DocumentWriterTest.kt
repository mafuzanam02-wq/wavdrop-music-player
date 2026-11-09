package com.launchpoint.wavdrop.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class M3u8DocumentWriterTest {
    private val bytes = "#EXTM3U\n#EXTINF:1,x\n/a.mp3\n".toByteArray()

    @Test fun `success only after write and close`() {
        val out = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val r = M3u8DocumentWriter.write(bytes) { out }
        assertEquals(ExportWriteResult.Success, r)
        assertTrue(out.closed); assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun `null stream is a failure`() {
        assertEquals(ExportWriteResult.NoOutputStream, M3u8DocumentWriter.write(bytes) { null })
    }

    @Test fun `truncating mode is tried first then plain write`() {
        val modes = mutableListOf<String>()
        val r = M3u8DocumentWriter.write(bytes) { mode ->
            modes += mode
            if (mode == "wt") null else ByteArrayOutputStream()
        }
        assertEquals(ExportWriteResult.Success, r); assertEquals(listOf("wt", "w"), modes)
    }

    @Test fun `write failure is a failure`() {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        assertTrue(M3u8DocumentWriter.write(bytes) { failing } is ExportWriteResult.Failed)
    }

    @Test fun `close failure is a failure not a success`() {
        val out = object : ByteArrayOutputStream() { override fun close() = throw IOException("provider error") }
        assertTrue(M3u8DocumentWriter.write(bytes) { out } is ExportWriteResult.Failed)
    }

    @Test fun `provider exception when opening is a failure`() {
        assertTrue(M3u8DocumentWriter.write(bytes) { throw SecurityException("nope") } is ExportWriteResult.Failed)
    }

    @Test fun `partial write then failure is a failure`() {
        var n = 0
        val out = object : OutputStream() {
            override fun write(b: Int) { if (++n > 5) throw IOException("cut") }
            override fun write(b: ByteArray, off: Int, len: Int) { for (i in 0 until len) write(b[off + i].toInt()) }
        }
        assertTrue(M3u8DocumentWriter.write(bytes) { out } is ExportWriteResult.Failed)
    }
}
