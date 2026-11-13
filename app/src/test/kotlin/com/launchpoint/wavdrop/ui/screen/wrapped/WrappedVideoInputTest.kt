package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P5-WR-VID-02B: every rule that must hold before any encoder object is created. Pure JVM, real temp files. */
class WrappedVideoInputTest {

    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("wrapped-video-input").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    /** A structurally valid PNG (signature, IHDR, one IDAT, IEND); CRCs are not checked by the validator. */
    private fun pngBytes(w: Int = 720, h: Int = 1280, withIend: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        int(13); out.write("IHDR".toByteArray()); int(w); int(h); out.write(byteArrayOf(8, 6, 0, 0, 0)); int(0)
        int(4); out.write("IDAT".toByteArray()); out.write(byteArrayOf(1, 2, 3, 4)); int(0)
        if (withIend) { int(0); out.write("IEND".toByteArray()); out.write(byteArrayOf(0xAE.toByte(), 0x42, 0x60, 0x82.toByte())) }
        return out.toByteArray()
    }

    private fun names(n: Int) = (1..n).map { "slide_%02d_page.png".format(it) }

    private fun entries(fileNames: List<String>, w: Int = 720, h: Int = 1280, durationMs: Int = 3_000) =
        fileNames.mapIndexed { i, name ->
            WrappedExportEntry(i, WrappedPageId.values()[i % WrappedPageId.values().size], name, w, h, durationMs)
        }

    private fun export(
        fileNames: List<String> = names(3),
        dirName: String = WRAPPED_EXPORT_DIR_PREFIX + "abc",
        writeFiles: Boolean = true,
        manifest: String? = null,
        entryList: List<WrappedExportEntry> = entries(fileNames),
    ): WrappedExportOutcome.Success {
        val dir = File(root, dirName).also { it.mkdirs() }
        if (writeFiles) for (n in fileNames) if (n.none { it == '/' || it == '\\' }) File(dir, n).writeBytes(pngBytes())
        val m = manifest ?: ("{\"exportId\":\"abc\",\"pageCount\":${fileNames.size},\"slides\":[" +
            fileNames.joinToString(",") { "{\"file\":\"$it\"}" } + "]}")
        File(dir, "manifest.json").writeText(m)
        return WrappedExportOutcome.Success(dir, entryList)
    }

    private fun problem(r: WrappedVideoInputResult) = (r as WrappedVideoInputResult.Invalid).problem

    @Test fun `a complete export is accepted in entry order with its durations`() {
        val e = export(names(4))
        val r = validateWrappedVideoInput(e) as WrappedVideoInputResult.Valid
        assertEquals(names(4), r.input.slides.map { it.fileName })
        assertEquals(listOf(0, 1, 2, 3), r.input.slides.map { it.index })
        assertTrue(r.input.slides.all { it.durationMs == 3_000 && it.file.parentFile == e.directory })
        assertEquals(12_000L, r.input.totalDurationMs)
        assertEquals(720 to 1280, r.input.widthPx to r.input.heightPx)
    }

    @Test fun `an empty export is rejected`() {
        assertEquals(WrappedVideoInputProblem.NoSlides, problem(validateWrappedVideoInput(export(emptyList()))))
    }

    @Test fun `entries must be contiguous and in order`() {
        val names = names(3)
        val skipped = listOf(entries(names)[0], entries(names)[2])
        assertEquals(
            WrappedVideoInputProblem.NotOrdered,
            problem(validateWrappedVideoInput(export(names, entryList = skipped))),
        )
        val swapped = entries(names).let { listOf(it[1], it[0], it[2]) }
        assertEquals(
            WrappedVideoInputProblem.NotOrdered,
            problem(validateWrappedVideoInput(export(names, entryList = swapped))),
        )
    }

    @Test fun `duplicate file names are rejected`() {
        val dup = listOf("slide_01_a.png", "slide_02_b.png", "SLIDE_02_B.png")
        assertEquals(WrappedVideoInputProblem.DuplicateFileName, problem(validateWrappedVideoInput(export(dup))))
    }

    @Test fun `path traversal and non-simple names never resolve`() {
        for (bad in listOf("../outside.png", "sub/slide.png", "sub\\slide.png", "..", "", "C:evil.png", "slide.txt")) {
            val e = export(listOf(bad), writeFiles = false)
            val p = problem(validateWrappedVideoInput(e))
            assertEquals("'$bad'", WrappedVideoInputProblem.UnsafeFileName, p)
        }
    }

    @Test fun `a file that is missing is rejected`() {
        val e = export(names(3))
        File(e.directory, names(3)[1]).delete()
        assertEquals(WrappedVideoInputProblem.MissingFile, problem(validateWrappedVideoInput(e)))
    }

    @Test fun `an empty file is rejected`() {
        val e = export(names(2))
        File(e.directory, names(2)[0]).writeBytes(ByteArray(0))
        assertEquals(WrappedVideoInputProblem.EmptyFile, problem(validateWrappedVideoInput(e)))
    }

    @Test fun `a file that is not a png is rejected`() {
        val e = export(names(2))
        File(e.directory, names(2)[1]).writeBytes(ByteArray(200) { 7 })
        assertEquals(WrappedVideoInputProblem.NotPng, problem(validateWrappedVideoInput(e)))
    }

    @Test fun `a truncated png is rejected without decoding it`() {
        val e = export(names(2))
        File(e.directory, names(2)[0]).writeBytes(pngBytes(withIend = false) + ByteArray(20))
        assertEquals(WrappedVideoInputProblem.Truncated, problem(validateWrappedVideoInput(e)))
    }

    @Test fun `declared and actual dimensions must both be the export size`() {
        val wrongDeclared = export(names(2), entryList = entries(names(2), w = 1280, h = 720))
        assertEquals(WrappedVideoInputProblem.DimensionMismatch, problem(validateWrappedVideoInput(wrongDeclared)))
        val wrongFile = export(names(2))
        File(wrongFile.directory, names(2)[1]).writeBytes(pngBytes(w = 360, h = 640))
        assertEquals(WrappedVideoInputProblem.DimensionMismatch, problem(validateWrappedVideoInput(wrongFile)))
    }

    @Test fun `durations must be positive`() {
        val zero = export(names(2), entryList = entries(names(2), durationMs = 0))
        assertEquals(WrappedVideoInputProblem.BadDuration, problem(validateWrappedVideoInput(zero)))
    }

    @Test fun `the platform decode check is honoured and files are visited sequentially`() {
        val e = export(names(3))
        val visited = ArrayList<String>()
        val r = validateWrappedVideoInput(e, decodable = { visited += it.name; it.name != names(3)[2] })
        assertEquals(WrappedVideoInputProblem.NotDecodable, problem(r))
        assertEquals(names(3), visited)
    }

    @Test fun `the manifest is cross-checked`() {
        val missing = export(names(2))
        File(missing.directory, "manifest.json").delete()
        assertEquals(WrappedVideoInputProblem.ManifestMissing, problem(validateWrappedVideoInput(missing)))

        val wrongCount = export(names(2), manifest = "{\"pageCount\":5,\"slides\":[]}")
        assertEquals(WrappedVideoInputProblem.ManifestMismatch, problem(validateWrappedVideoInput(wrongCount)))

        val n = names(2)
        val reordered = export(n, manifest = "{\"pageCount\":2,\"slides\":[{\"file\":\"${n[1]}\"},{\"file\":\"${n[0]}\"}]}")
        assertEquals(WrappedVideoInputProblem.ManifestMismatch, problem(validateWrappedVideoInput(reordered)))
    }

    @Test fun `a directory that is not an owned slide export is rejected`() {
        val e = export(names(2), dirName = "somebody_elses_folder")
        assertEquals(WrappedVideoInputProblem.NotOwnedDirectory, problem(validateWrappedVideoInput(e)))
    }

    @Test fun `png header reader recognises valid truncated and foreign files`() {
        val ok = File(root, "ok.png").also { it.writeBytes(pngBytes(720, 1280)) }
        val h = readWrappedPngHeader(ok)!!
        assertEquals(720 to 1280, h.width to h.height)
        assertNull(readWrappedPngHeader(File(root, "x.bin").also { it.writeBytes(ByteArray(100)) }))
        assertNull(readWrappedPngHeader(File(root, "tiny.png").also { it.writeBytes(ByteArray(10)) }))
    }
}
