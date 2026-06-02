package com.launchpoint.wavdrop.data.lyrics

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3LyricsParserTest {

    @Test
    fun `parses id3v2_3 uslt unsynchronised lyrics frame`() {
        val bytes = id3v23Tag(
            frameId = "USLT",
            frameBody = usltBody(
                lyrics = "Line one\nLine two",
                description = "BlackPlayer",
            ),
        )

        assertEquals(
            "Line one\nLine two",
            Id3LyricsParser.parseUnsynchronisedLyrics(bytes),
        )
    }

    @Test
    fun `parses id3v2_4 uslt frame with syncsafe frame size`() {
        val bytes = id3v24Tag(
            frameId = "USLT",
            frameBody = usltBody(lyrics = "Synced? Not yet.\nPlain lyrics."),
        )

        assertEquals(
            "Synced? Not yet.\nPlain lyrics.",
            Id3LyricsParser.parseUnsynchronisedLyrics(bytes),
        )
    }

    @Test
    fun `returns null when id3 tag has no uslt frame`() {
        val bytes = id3v23Tag(
            frameId = "TIT2",
            frameBody = byteArrayOf(3) + "Song title".toByteArray(StandardCharsets.UTF_8),
        )

        assertNull(Id3LyricsParser.parseUnsynchronisedLyrics(bytes))
    }

    @Test
    fun `returns null for blank uslt text`() {
        val bytes = id3v23Tag(
            frameId = "USLT",
            frameBody = usltBody(lyrics = "   \n  "),
        )

        assertNull(Id3LyricsParser.parseUnsynchronisedLyrics(bytes))
    }

    private fun usltBody(
        lyrics: String,
        description: String = "",
    ): ByteArray =
        byteArrayOf(3) +
            "eng".toByteArray(StandardCharsets.ISO_8859_1) +
            description.toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf(0) +
            lyrics.toByteArray(StandardCharsets.UTF_8)

    private fun id3v23Tag(frameId: String, frameBody: ByteArray): ByteArray {
        val frame = frameId.toByteArray(StandardCharsets.ISO_8859_1) +
            intBytes(frameBody.size) +
            byteArrayOf(0, 0) +
            frameBody

        return id3Header(majorVersion = 3, tagSize = frame.size) + frame
    }

    private fun id3v24Tag(frameId: String, frameBody: ByteArray): ByteArray {
        val frame = frameId.toByteArray(StandardCharsets.ISO_8859_1) +
            syncSafeBytes(frameBody.size) +
            byteArrayOf(0, 0) +
            frameBody

        return id3Header(majorVersion = 4, tagSize = frame.size) + frame
    }

    private fun id3Header(majorVersion: Int, tagSize: Int): ByteArray =
        "ID3".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(majorVersion.toByte(), 0, 0) +
            syncSafeBytes(tagSize)

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun syncSafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
