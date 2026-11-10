package com.abonoura.aura

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads a subtitle file whatever its encoding. Order: a byte-order mark, then strict UTF-8, then
 * Arabic Windows-1256 (by far the most common legacy encoding for Arabic subtitles), then Latin.
 */
object SubtitleText {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        val arabic = runCatching { String(bytes, Charset.forName("windows-1256")) }.getOrNull()
        val latin = String(bytes, Charsets.ISO_8859_1)
        if (arabic == null) return latin
        // In a real Arabic file most of the non-ASCII letters land in the Arabic block.
        val high = arabic.count { it.code > 0x7F }
        val arabicLetters = arabic.count { it in '؀'..'ۿ' }
        return if (high > 0 && arabicLetters * 2 >= high) arabic else latin
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
