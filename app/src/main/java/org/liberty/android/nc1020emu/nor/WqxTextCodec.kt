package org.liberty.android.nc1020emu.nor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object WqxTextCodec {
    const val MAX_NAME_GBK_BYTES = 16
    val GBK_CHARSET: Charset = Charset.forName("GBK")

    private val FORBIDDEN_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    data class TextUploadPrepResult(
        val gbkBytes: ByteArray,
        val wasUtf8: Boolean,
        val unmappableCharCount: Int
    )

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
     * Converts UTF-8 text (or normalizes line breaks) into WQX-ready GBK/GB2312 bytes.
     * If input is already non-UTF-8 GBK/GB2312, returns the raw bytes untouched when convertUtf8 is true.
     */
    fun prepareTextForUpload(rawBytes: ByteArray, convertUtf8ToGbk: Boolean): TextUploadPrepResult {
        if (!convertUtf8ToGbk || rawBytes.isEmpty()) {
            return TextUploadPrepResult(rawBytes, wasUtf8 = false, unmappableCharCount = 0)
        }
        val utf8Detected = hasUtf8Bom(rawBytes) || isValidUtf8(rawBytes)
        if (!utf8Detected) {
            return TextUploadPrepResult(rawBytes, wasUtf8 = false, unmappableCharCount = 0)
        }
        val start = if (hasUtf8Bom(rawBytes)) 3 else 0
        val rawText = String(rawBytes, start, rawBytes.size - start, Charsets.UTF_8)
        val normalized = normalizeTypographyAndNewlines(rawText)
        val (encoded, unmappable) = encodeStringToGbkWithCount(normalized)
        return TextUploadPrepResult(
            gbkBytes = encoded,
            wasUtf8 = hasUtf8Bom(rawBytes) || hasHighBitBytes(rawBytes),
            unmappableCharCount = unmappable
        )
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
