package com.launchpoint.wavdrop.data.lyrics

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object Id3LyricsParser {

    fun parseUnsynchronisedLyrics(bytes: ByteArray): String? {
        if (bytes.size < ID3_HEADER_SIZE) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return null
        }

        val majorVersion = bytes[3].toInt() and 0xFF
        if (majorVersion !in 2..4) return null

        val flags = bytes[5].toInt() and 0xFF
        val tagSize = readSyncSafeInt(bytes, 6).coerceAtMost(bytes.size - ID3_HEADER_SIZE)
        val tagStart = ID3_HEADER_SIZE
        val tagEnd = tagStart + tagSize
        if (tagEnd <= tagStart || tagEnd > bytes.size) return null

        val tagBytes = bytes.copyOfRange(tagStart, tagEnd)
            .let { if ((flags and TAG_FLAG_UNSYNCHRONISATION) != 0) removeUnsynchronisation(it) else it }

        return when (majorVersion) {
            2 -> parseV22Frames(tagBytes)
            else -> parseV23OrV24Frames(tagBytes, useSyncSafeFrameSize = majorVersion == 4)
        }
    }

    private fun parseV23OrV24Frames(
        tagBytes: ByteArray,
        useSyncSafeFrameSize: Boolean,
    ): String? {
        var offset = 0
        while (offset + V23_FRAME_HEADER_SIZE <= tagBytes.size) {
            val frameId = tagBytes.decodeAscii(offset, 4)
            if (frameId.isBlank() || frameId.any { it.code == 0 }) return null

            val frameSize = if (useSyncSafeFrameSize) {
                readSyncSafeInt(tagBytes, offset + 4)
            } else {
                readInt(tagBytes, offset + 4)
            }
            if (frameSize <= 0) return null

            val frameStart = offset + V23_FRAME_HEADER_SIZE
            val frameEnd = frameStart + frameSize
            if (frameEnd > tagBytes.size) return null

            if (frameId == "USLT") {
                decodeUsltFrame(tagBytes.copyOfRange(frameStart, frameEnd))?.let { return it }
            }

            offset = frameEnd
        }
        return null
    }

    private fun parseV22Frames(tagBytes: ByteArray): String? {
        var offset = 0
        while (offset + V22_FRAME_HEADER_SIZE <= tagBytes.size) {
            val frameId = tagBytes.decodeAscii(offset, 3)
            if (frameId.isBlank() || frameId.any { it.code == 0 }) return null

            val frameSize = read24BitInt(tagBytes, offset + 3)
            if (frameSize <= 0) return null

            val frameStart = offset + V22_FRAME_HEADER_SIZE
            val frameEnd = frameStart + frameSize
            if (frameEnd > tagBytes.size) return null

            if (frameId == "ULT") {
                decodeUsltFrame(tagBytes.copyOfRange(frameStart, frameEnd))?.let { return it }
            }

            offset = frameEnd
        }
        return null
    }

    private fun decodeUsltFrame(frame: ByteArray): String? {
        if (frame.size <= USLT_HEADER_SIZE) return null

        val encoding = frame[0].toInt() and 0xFF
        val lyricsStart = findDescriptionEnd(frame, encoding)
        if (lyricsStart !in 1 until frame.size) return null

        val textBytes = frame.copyOfRange(lyricsStart, frame.size)
        val text = decodeText(textBytes, encoding).trim('\uFEFF', ' ', '\t', '\r', '\n')
        return LyricsTextCleaner.clean(text)
    }

    private fun findDescriptionEnd(frame: ByteArray, encoding: Int): Int {
        var offset = USLT_HEADER_SIZE
        if (usesTwoByteTerminator(encoding)) {
            while (offset + 1 < frame.size) {
                if (frame[offset] == 0.toByte() && frame[offset + 1] == 0.toByte()) {
                    return offset + 2
                }
                offset += 2
            }
        } else {
            while (offset < frame.size) {
                if (frame[offset] == 0.toByte()) return offset + 1
                offset += 1
            }
        }
        return -1
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String {
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        return bytes.toString(charset)
    }

    private fun usesTwoByteTerminator(encoding: Int): Boolean =
        encoding == 1 || encoding == 2

    private fun removeUnsynchronisation(bytes: ByteArray): ByteArray {
        val output = ArrayList<Byte>(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val current = bytes[index]
            output.add(current)
            if (
                current == 0xFF.toByte() &&
                index + 1 < bytes.size &&
                bytes[index + 1] == 0.toByte()
            ) {
                index += 2
            } else {
                index += 1
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).toString(StandardCharsets.ISO_8859_1)

    private fun readSyncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun read24BitInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private const val ID3_HEADER_SIZE = 10
    private const val V23_FRAME_HEADER_SIZE = 10
    private const val V22_FRAME_HEADER_SIZE = 6
    private const val USLT_HEADER_SIZE = 4
    private const val TAG_FLAG_UNSYNCHRONISATION = 0x80
}
