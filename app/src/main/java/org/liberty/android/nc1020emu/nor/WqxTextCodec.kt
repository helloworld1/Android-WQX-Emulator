package org.liberty.android.nc1020emu.nor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object WqxTextCodec {
    const val MAX_NAME_GBK_BYTES = 16
    const val WQX_BOOK_HEADER_SIZE = 24 // 15-byte main header + 9-byte section 0 descriptor
    val GBK_CHARSET: Charset = Charset.forName("GBK")

    private val FORBIDDEN_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    data class TextUploadPrepResult(
        val gbkBytes: ByteArray,
        val wasUtf8: Boolean,
        val unmappableCharCount: Int
    )

    fun hasWqxBookHeader(bytes: ByteArray): Boolean {
        if (bytes.size < WQX_BOOK_HEADER_SIZE) return false
        if (bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 0.toByte()) {
            return false
        }
        val tocCount = readU16Le(bytes, 3)
        if (tocCount == 0) {
            val sectionCount = readU16Le(bytes, 5)
            val expectedMinData = 15 + sectionCount * 9
            if (sectionCount !in 1..512 || bytes.size < expectedMinData) return false
            val globalStart = readU24Le(bytes, 7)
            val globalEnd = readU24Le(bytes, 10)
            if (globalStart < expectedMinData || globalEnd < globalStart || globalEnd > bytes.size) {
                return false
            }
            val secType = bytes[15].toInt() and 0xFF
            val secStart = readU24Le(bytes, 16)
            val secEnd = readU24Le(bytes, 19)
            return secType in 0..4 && secStart in expectedMinData..bytes.size && secEnd in secStart..bytes.size
        }
        // Heuristic for multi-chapter .ebk (TOC count > 0, followed by chapter list ending in 0xFF)
        if (tocCount in 1..1024 && bytes[15] != 0xFF.toByte()) {
            val scanLimit = minOf(bytes.size, 256)
            for (i in 15 until scanLimit) {
                if (bytes[i] == 0xFF.toByte()) return true
            }
        }
        return false
    }

    fun extractReadableTextBytes(bytes: ByteArray): ByteArray {
        if (!hasWqxBookHeader(bytes)) return bytes
        val tocCount = readU16Le(bytes, 3)
        if (tocCount != 0) return bytes
        val sectionCount = readU16Le(bytes, 5)
        val out = ByteArrayOutputStream(bytes.size)
        for (s in 0 until sectionCount) {
            val descOff = 15 + s * 9
            if (descOff + 9 > bytes.size) break
            val secType = bytes[descOff].toInt() and 0xFF
            val secStart = readU24Le(bytes, descOff + 1)
            val secEnd = readU24Le(bytes, descOff + 4)
            if (secType == 0 && secStart >= 15 + sectionCount * 9 && secEnd >= secStart && secEnd <= bytes.size) {
                if (out.size() > 0) {
                    out.write('\r'.code)
                    out.write('\n'.code)
                }
                out.write(bytes, secStart, secEnd - secStart)
            }
        }
        return if (out.size() > 0) out.toByteArray() else bytes
    }

    fun decodeWqxDocument(bytes: ByteArray): String {
        val body = extractReadableTextBytes(bytes)
        return decodeGbk(body)
    }

    fun wrapAsWqxTxt(bodyBytes: ByteArray): ByteArray {
        val start = WQX_BOOK_HEADER_SIZE
        val end = WQX_BOOK_HEADER_SIZE + bodyBytes.size
        val out = ByteArray(end)
        // [0..2] base offset = 0
        // [3..4] toc_count = 0 (single document)
        // [5..6] section_count = 1
        out[5] = 0x01
        out[6] = 0x00
        // [7..9] global start offset = 24
        writeU24Le(out, 7, start)
        // [10..12] global end offset = 24 + len
        writeU24Le(out, 10, end)
        // [13..14] reserved = 0
        // [15] section 0 type = 0 (plain text viewer)
        out[15] = 0x00
        // [16..18] section 0 start offset = 24
        writeU24Le(out, 16, start)
        // [19..21] section 0 end offset = 24 + len
        writeU24Le(out, 19, end)
        // [22..23] section 0 reserved = 0
        System.arraycopy(bodyBytes, 0, out, WQX_BOOK_HEADER_SIZE, bodyBytes.size)
        return out
    }

    private fun readU16Le(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun readU24Le(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16)

    private fun writeU24Le(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = (value and 0xFF).toByte()
        bytes[off + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[off + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    fun decodeGbk(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""
        return String(bytes, offset, length, GBK_CHARSET)
    }

    fun isValidUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val start = if (hasUtf8Bom(bytes)) 3 else 0
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()

    fun hasHighBitBytes(bytes: ByteArray): Boolean =
        bytes.any { (it.toInt() and 0x80) != 0 }

    fun decodeSmartText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return if (hasUtf8Bom(bytes) || (hasHighBitBytes(bytes) && isValidUtf8(bytes))) {
            val start = if (hasUtf8Bom(bytes)) 3 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        } else {
            decodeGbk(bytes)
        }
    }

    /**
     * Converts UTF-8 text to GBK/GB2312 (preserving already-GBK bytes) and wraps plain text
     * in the 24-byte WQX single-section header required by NC1020's built-in "文书浏览" viewer.
     */
    fun prepareTextForUpload(rawBytes: ByteArray, formatForWqxReader: Boolean): TextUploadPrepResult {
        if (!formatForWqxReader) {
            return TextUploadPrepResult(rawBytes, wasUtf8 = false, unmappableCharCount = 0)
        }
        if (hasWqxBookHeader(rawBytes)) {
            return TextUploadPrepResult(rawBytes, wasUtf8 = false, unmappableCharCount = 0)
        }
        val utf8WithHighBits = hasUtf8Bom(rawBytes) || (hasHighBitBytes(rawBytes) && isValidUtf8(rawBytes))
        val bodyGbkBytes: ByteArray
        val unmappable: Int
        if (utf8WithHighBits) {
            val start = if (hasUtf8Bom(rawBytes)) 3 else 0
            val rawText = String(rawBytes, start, rawBytes.size - start, Charsets.UTF_8)
            val normalized = normalizeTypographyAndNewlines(rawText)
            val (encoded, badCount) = encodeStringToGbkWithCount(normalized)
            bodyGbkBytes = encoded
            unmappable = badCount
        } else {
            bodyGbkBytes = normalizeCrlfBytes(rawBytes)
            unmappable = 0
        }
        return TextUploadPrepResult(
            gbkBytes = wrapAsWqxTxt(bodyGbkBytes),
            wasUtf8 = utf8WithHighBits,
            unmappableCharCount = unmappable
        )
    }

    private fun normalizeCrlfBytes(rawBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rawBytes.size + 16)
        var i = 0
        while (i < rawBytes.size) {
            val b = rawBytes[i].toInt() and 0xFF
            if (b == 0x0D) {
                out.write(0x0D)
                out.write(0x0A)
                if (i + 1 < rawBytes.size && (rawBytes[i + 1].toInt() and 0xFF) == 0x0A) {
                    i++
                }
            } else if (b == 0x0A) {
                out.write(0x0D)
                out.write(0x0A)
            } else {
                out.write(b)
            }
            i++
        }
        return out.toByteArray()
    }

    private fun normalizeTypographyAndNewlines(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    sb.append("\r\n")
                    if (i + 1 < text.length && text[i + 1] == '\n') {
                        i++
                    }
                }
                '\n' -> sb.append("\r\n")
                '\u00A0' -> sb.append(' ')
                '\u2022' -> sb.append('·')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun encodeStringToGbkWithCount(text: String): Pair<ByteArray, Int> {
        val encoder = GBK_CHARSET.newEncoder()
        val out = ByteArrayOutputStream(text.length * 2)
        var unmappable = 0
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            val charCount = Character.charCount(codePoint)
            val sub = text.substring(i, i + charCount)
            if (encoder.canEncode(sub)) {
                val bytes = sub.toByteArray(GBK_CHARSET)
                out.write(bytes)
            } else {
                out.write('?'.code)
                unmappable++
            }
            i += charCount
        }
        return out.toByteArray() to unmappable
    }

    fun gbkByteLength(name: String): Int =
        name.trim().toByteArray(GBK_CHARSET).size

    fun proposeWqxFileName(rawDisplayName: String, forceExtension: String? = null): String {
        val justName = rawDisplayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .filter { it.code >= 0x20 && it !in FORBIDDEN_NAME_CHARS }
            .ifEmpty { "file" }

        val dotIdx = justName.lastIndexOf('.')
        val rawBase = if (dotIdx > 0) justName.substring(0, dotIdx) else justName
        val rawExt = if (dotIdx > 0 && dotIdx < justName.length - 1) {
            justName.substring(dotIdx + 1)
        } else {
            ""
        }
        val finalExt = (forceExtension ?: rawExt).trim()
        val extSuffix = if (finalExt.isNotEmpty()) ".$finalExt" else ""
        val extBytes = extSuffix.toByteArray(GBK_CHARSET)
        if (extBytes.size >= MAX_NAME_GBK_BYTES) {
            return truncateToMaxGbkBytes(justName, MAX_NAME_GBK_BYTES)
        }

        val maxBaseBytes = MAX_NAME_GBK_BYTES - extBytes.size
        val cleanBase = truncateToMaxGbkBytes(rawBase.ifEmpty { "file" }, maxBaseBytes)
            .ifEmpty { "f" }
        return cleanBase + extSuffix
    }

    fun truncateToMaxGbkBytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val cc = Character.charCount(cp)
            val sub = text.substring(i, i + cc)
            val bLen = sub.toByteArray(GBK_CHARSET).size
            if (used + bLen > maxBytes) break
            sb.append(sub)
            used += bLen
            i += cc
        }
        return sb.toString()
    }

    sealed class NameValidation {
        data class Valid(val gbkBytes: ByteArray, val trimmedName: String) : NameValidation()
        data class Invalid(val reason: Reason, val byteCount: Int) : NameValidation()

        enum class Reason {
            EMPTY,
            TOO_LONG,
            FORBIDDEN_CHAR,
            UNMAPPABLE_CHAR
        }
    }

    fun validateWqxName(input: String): NameValidation {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            return NameValidation.Invalid(NameValidation.Reason.EMPTY, 0)
        }
        if (trimmed.any { it.code < 0x20 || it in FORBIDDEN_NAME_CHARS }) {
            return NameValidation.Invalid(
                NameValidation.Reason.FORBIDDEN_CHAR,
                trimmed.toByteArray(GBK_CHARSET).size
            )
        }
        val encoder = GBK_CHARSET.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encodedBytes = try {
            val buf = encoder.encode(CharBuffer.wrap(trimmed))
            ByteArray(buf.remaining()).also { buf.get(it) }
        } catch (_: Exception) {
            return NameValidation.Invalid(
                NameValidation.Reason.UNMAPPABLE_CHAR,
                trimmed.toByteArray(GBK_CHARSET).size
            )
        }
        if (encodedBytes.isEmpty()) {
            return NameValidation.Invalid(NameValidation.Reason.EMPTY, 0)
        }
        if (encodedBytes.size > MAX_NAME_GBK_BYTES) {
            return NameValidation.Invalid(NameValidation.Reason.TOO_LONG, encodedBytes.size)
        }
        return NameValidation.Valid(encodedBytes, trimmed)
    }

    fun extractReadableStrings(bytes: ByteArray, minChars: Int = 2, maxOutputChars: Int = 8000): String {
        if (bytes.isEmpty()) return ""
        val results = ArrayList<String>()
        var totalChars = 0
        var i = 0
        while (i < bytes.size && totalChars < maxOutputChars) {
            val start = i
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                if (b0 in 0x20..0x7E) {
                    i++
                } else if (b0 in 0xA1..0xF7 && i + 1 < bytes.size) {
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 in 0xA1..0xFE) {
                        i += 2
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            if (i - start >= minChars) {
                val str = decodeGbk(bytes, start, i - start).trim()
                if (str.length >= minChars) {
                    val line = "[0x%04X] %s".format(start, str)
                    results.add(line)
                    totalChars += line.length + 1
                }
            }
            if (i == start) i++
        }
        return results.joinToString("\n")
    }

    fun formatHexDump(bytes: ByteArray, maxBytes: Int = 16384): String {
        if (bytes.isEmpty()) return "(0 bytes)"
        val limit = minOf(bytes.size, maxBytes)
        val sb = StringBuilder((limit / 16 + 2) * 74)
        var off = 0
        while (off < limit) {
            val rowLen = minOf(16, limit - off)
            sb.append("%06X: ".format(off))
            for (j in 0 until 16) {
                if (j < rowLen) {
                    sb.append("%02X ".format(bytes[off + j].toInt() and 0xFF))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(' ')
            }
            sb.append(" |")
            var j = 0
            while (j < rowLen) {
                val b0 = bytes[off + j].toInt() and 0xFF
                if (b0 in 0x20..0x7E) {
                    sb.append(b0.toChar())
                    j++
                } else if (b0 in 0xA1..0xF7 && j + 1 < rowLen) {
                    val b1 = bytes[off + j + 1].toInt() and 0xFF
                    if (b1 in 0xA1..0xFE) {
                        sb.append(decodeGbk(bytes, off + j, 2))
                        j += 2
                    } else {
                        sb.append('.')
                        j++
                    }
                } else {
                    sb.append('.')
                    j++
                }
            }
            sb.append("|\n")
            off += 16
        }
        if (bytes.size > limit) {
            sb.append("\n... (+${bytes.size - limit} more bytes truncated in preview)")
        }
        return sb.toString()
    }
}
