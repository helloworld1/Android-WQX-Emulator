package org.liberty.android.nc1020emu.nor

import java.io.ByteArrayOutputStream

object GvBasicCodec {
    const val MAX_BAS_FILE_BYTES = 8192
    private const val BASE_RAM_ADDR = 0x2001
    private const val TOKEN_START = 0x80
    private const val TOKEN_DATA = 0x83
    private const val TOKEN_REM = 0x93
    private const val TOKEN_PRINT = 0x98
    private const val GBK_ESCAPE_PREFIX = 0x1F

    // Exact 110-entry token table from NC1020 ROM Vol 0 Bank 0x9A ($42EC..$4485)
    val TOKENS: List<String> = listOf(
        "END", "FOR", "NEXT", "DATA", "INPUT", "DEL", "DIM", "READ",
        "SWAP", "TRACE", "NOTRACE", "POP", "LET", "GOTO", "RUN", "IF",
        "RESTORE", "GOSUB", "RETURN", "REM", "STOP", "ON", "DEF", "POKE",
        "PRINT", "CONT", "LIST", "CLEAR", "NEW", "TEXT", "GRAPH", "SYSTEM",
        "NORMAL", "INVERSE", "FLASH", "PLAY", "BEEP", "INKEY$", "LOAD", "SAVE",
        "KILL", "FILES", "OPEN", "CLOSE", "WRITE", "FIELD", "GET", "PUT",
        "LSET", "RSET", "AUTO", "LOCATE", "DRAW", "LINE", "BOX", "CIRCLE",
        "ELLIPSE", "CLS", "EDIT", "WHILE", "WEND", "CALL", "RENAME", "COPY",
        "TAB", "TO", "FN", "SPC", "THEN", "ELSE", "AT", "NOT",
        "STEP", "+", "-", "*", "/", "^", "AND", "OR",
        ">", "=", "<", "SGN", "INT", "ABS", "POS", "SQR",
        "RND", "LOG", "EXP", "COS", "SIN", "TAN", "ATN", "PEEK",
        "LEN", "STR$", "VAL", "ASC", "MKS$", "MKI$", "CVS$", "CVI$",
        "LOF", "EOF", "CHR$", "LEFT$", "RIGHT$", "MID$"
    )

    private val TOKENS_BY_LENGTH_DESC: List<Pair<String, Int>> =
        TOKENS.mapIndexed { idx, kw -> kw to (TOKEN_START + idx) }
            .sortedByDescending { it.first.length }

    fun isTokenizedBas(bytes: ByteArray): Boolean {
        if (bytes.size < 3 || bytes.size > MAX_BAS_FILE_BYTES) return false
        if (bytes[0] != 0x00.toByte()) return false
        var pos = 1
        var linesSeen = 0
        while (pos + 2 <= bytes.size) {
            val nextLo = bytes[pos].toInt() and 0xFF
            val nextHi = bytes[pos + 1].toInt() and 0xFF
            val nextAddr = nextLo or (nextHi shl 8)
            if (nextAddr == 0) {
                return linesSeen > 0 || bytes.size == 3
            }
            if (nextAddr < BASE_RAM_ADDR || nextAddr > BASE_RAM_ADDR + MAX_BAS_FILE_BYTES) {
                return false
            }
            if (pos + 4 >= bytes.size) return false
            var p = pos + 4
            while (p < bytes.size && bytes[p] != 0x00.toByte()) {
                if ((bytes[p].toInt() and 0xFF) == GBK_ESCAPE_PREFIX) {
                    p += 3
                } else {
                    p++
                }
            }
            if (p >= bytes.size || bytes[p] != 0x00.toByte()) return false
            pos = p + 1
            linesSeen++
        }
        return false
    }

    fun detokenizeToSource(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (!isTokenizedBas(bytes)) {
            return WqxTextCodec.decodeSmartText(bytes)
        }
        val sb = StringBuilder(bytes.size * 2)
        var pos = 1
        while (pos + 2 <= bytes.size) {
            val nextAddr = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            if (nextAddr == 0 || pos + 4 >= bytes.size) break
            val lineNum = (bytes[pos + 2].toInt() and 0xFF) or ((bytes[pos + 3].toInt() and 0xFF) shl 8)
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lineNum).append(' ')

            var p = pos + 4
            var inQuotes = false
            var inRem = false
            var prevWasWordToken = false
            while (p < bytes.size && bytes[p] != 0x00.toByte()) {
                val b = bytes[p].toInt() and 0xFF
                if (b == GBK_ESCAPE_PREFIX && p + 2 < bytes.size) {
                    sb.append(WqxTextCodec.decodeGbk(bytes, p + 1, 2))
                    p += 3
                    prevWasWordToken = false
                    continue
                }
                if (inQuotes || inRem) {
                    if (b == '"'.code && inQuotes) {
                        inQuotes = false
                        sb.append('"')
                        p++
                    } else if (b in 0xA1..0xF7 && p + 1 < bytes.size &&
                        (bytes[p + 1].toInt() and 0xFF) in 0xA1..0xFE
                    ) {
                        sb.append(WqxTextCodec.decodeGbk(bytes, p, 2))
                        p += 2
                    } else {
                        sb.append(b.toChar())
                        p++
                    }
                    prevWasWordToken = false
                    continue
                }

                if (b == '"'.code) {
                    if (prevWasWordToken) sb.append(' ')
                    inQuotes = true
                    sb.append('"')
                    prevWasWordToken = false
                    p++
                } else if (b in TOKEN_START until (TOKEN_START + TOKENS.size)) {
                    val kw = TOKENS[b - TOKEN_START]
                    val isWord = kw[0].isLetter()
                    if (isWord && sb.isNotEmpty() && sb.last().let { it.isLetterOrDigit() || it == '$' }) {
                        sb.append(' ')
                    }
                    sb.append(kw)
                    val endsWithParenOrSymbol = !kw.last().isLetter() && kw.last() != '$'
                    prevWasWordToken = isWord && !endsWithParenOrSymbol
                    if (b == TOKEN_REM) {
                        sb.append(' ')
                        inRem = true
                        prevWasWordToken = false
                    }
                    p++
                } else if (b in 0xA1..0xF7 && p + 1 < bytes.size &&
                    (bytes[p + 1].toInt() and 0xFF) in 0xA1..0xFE
                ) {
                    sb.append(WqxTextCodec.decodeGbk(bytes, p, 2))
                    p += 2
                    prevWasWordToken = false
                } else {
                    val ch = b.toChar()
                    if (prevWasWordToken && (ch.isLetterOrDigit())) {
                        sb.append(' ')
                    }
                    sb.append(ch)
                    prevWasWordToken = false
                    p++
                }
            }
            pos = if (p < bytes.size) p + 1 else bytes.size
        }
        return sb.toString()
    }

    sealed class BasUploadResult {
        data class Success(val tokenizedBytes: ByteArray, val wasPlainText: Boolean, val lineCount: Int) : BasUploadResult()
        data class Error(val message: String) : BasUploadResult()
    }

    fun prepareBasForUpload(rawBytes: ByteArray, autoTokenizePlainText: Boolean): BasUploadResult {
        if (rawBytes.size > MAX_BAS_FILE_BYTES && isTokenizedBas(rawBytes)) {
            return BasUploadResult.Error("GVBASIC .bas exceeds 8 KB limit (${rawBytes.size} bytes)")
        }
        if (isTokenizedBas(rawBytes) || !autoTokenizePlainText) {
            if (rawBytes.size > MAX_BAS_FILE_BYTES) {
                return BasUploadResult.Error("GVBASIC .bas exceeds 8 KB limit (${rawBytes.size} bytes)")
            }
            return BasUploadResult.Success(rawBytes, wasPlainText = false, lineCount = -1)
        }
        val text = WqxTextCodec.decodeSmartText(rawBytes)
        return tokenizeSource(text)
    }

    fun tokenizeSource(sourceText: String): BasUploadResult {
        val rawLines = sourceText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val parsedLines = ArrayList<Pair<Int, ByteArray>>()
        var nextAutoLine = 10

        for (rawLine in rawLines) {
            val trimmedStart = rawLine.trimStart()
            if (trimmedStart.isEmpty()) continue

            var idx = 0
            while (idx < trimmedStart.length && trimmedStart[idx].isDigit()) {
                idx++
            }
            val lineNum: Int
            val body: String
            if (idx > 0) {
                val parsedNum = trimmedStart.substring(0, idx).toIntOrNull()
                    ?: return BasUploadResult.Error("Invalid BASIC line number near: ${trimmedStart.take(24)}")
                if (parsedNum !in 0..65535) {
                    return BasUploadResult.Error("BASIC line number out of range (0..65535): $parsedNum")
                }
                lineNum = parsedNum
                nextAutoLine = (lineNum + 10).coerceAtMost(65535)
                var bodyStart = idx
                if (bodyStart < trimmedStart.length && trimmedStart[bodyStart] == ' ') {
                    bodyStart++
                }
                body = trimmedStart.substring(bodyStart)
            } else {
                lineNum = nextAutoLine
                nextAutoLine = (nextAutoLine + 10).coerceAtMost(65535)
                body = trimmedStart
            }

            val tokenizedBody = tokenizeLineBody(body)
            parsedLines.add(lineNum to tokenizedBody)
        }

        parsedLines.sortBy { it.first }

        val out = ByteArrayOutputStream(parsedLines.size * 24 + 8)
        out.write(0x00) // $2000 header byte
        var currentAddr = BASE_RAM_ADDR
        for ((lineNum, bodyBytes) in parsedLines) {
            val nextAddr = currentAddr + 4 + bodyBytes.size + 1
            out.write(nextAddr and 0xFF)
            out.write((nextAddr shr 8) and 0xFF)
            out.write(lineNum and 0xFF)
            out.write((lineNum shr 8) and 0xFF)
            out.write(bodyBytes)
            out.write(0x00)
            currentAddr = nextAddr
        }
        // End-of-program link (0x0000)
        out.write(0x00)
        out.write(0x00)

        val bytes = out.toByteArray()
        if (bytes.size > MAX_BAS_FILE_BYTES) {
            return BasUploadResult.Error("Tokenized GVBASIC program exceeds 8 KB (${bytes.size} bytes)")
        }
        return BasUploadResult.Success(bytes, wasPlainText = true, lineCount = parsedLines.size)
    }

    private fun tokenizeLineBody(body: String): ByteArray {
        val out = ByteArrayOutputStream(body.length + 8)
        var i = 0
        var inQuotes = false
        var inRem = false
        var inData = false
        var prevWasRawIdentLetter = false

        while (i < body.length) {
            val ch = body[i]
            if (inRem) {
                writeCharOrEscapedGbk(out, body, i).also { i += it }
                continue
            }
            if (inQuotes) {
                if (ch == '"') {
                    out.write('"'.code)
                    inQuotes = false
                    i++
                } else {
                    writeCharOrEscapedGbk(out, body, i).also { i += it }
                }
                continue
            }
            if (inData) {
                if (ch == ':') {
                    inData = false
                    prevWasRawIdentLetter = false
                    out.write(':'.code)
                    i++
                } else if (ch == '"') {
                    inQuotes = true
                    out.write('"'.code)
                    i++
                } else {
                    writeCharOrEscapedGbk(out, body, i).also { i += it }
                }
                continue
            }

            if (ch == ' ' || ch == '\t') {
                prevWasRawIdentLetter = false
                i++
                continue
            }
            if (ch == '"') {
                inQuotes = true
                prevWasRawIdentLetter = false
                out.write('"'.code)
                i++
                continue
            }
            if (ch == '?') {
                out.write(TOKEN_PRINT)
                prevWasRawIdentLetter = false
                i++
                continue
            }
            if (ch == '\'') {
                out.write(':'.code)
                out.write(TOKEN_REM)
                inRem = true
                prevWasRawIdentLetter = false
                i++
                continue
            }

            var matchedToken: Pair<String, Int>? = null
            for (entry in TOKENS_BY_LENGTH_DESC) {
                val kw = entry.first
                if (kw[0].isLetter() && prevWasRawIdentLetter) {
                    continue
                }
                if (body.regionMatches(i, kw, 0, kw.length, ignoreCase = true)) {
                    matchedToken = entry
                    break
                }
            }
            if (matchedToken != null) {
                val (kw, tok) = matchedToken
                out.write(tok)
                prevWasRawIdentLetter = false
                i += kw.length
                if (tok == TOKEN_REM) {
                    inRem = true
                    if (i < body.length && body[i] == ' ') i++
                } else if (tok == TOKEN_DATA) {
                    inData = true
                    if (i < body.length && body[i] == ' ') i++
                }
                continue
            }

            if (ch.code < 0x80) {
                out.write(ch.uppercaseChar().code)
                prevWasRawIdentLetter = ch.isLetter()
                i++
            } else {
                prevWasRawIdentLetter = false
                writeCharOrEscapedGbk(out, body, i).also { i += it }
            }
        }
        return out.toByteArray()
    }

    private fun writeCharOrEscapedGbk(out: ByteArrayOutputStream, text: String, index: Int): Int {
        val cp = Character.codePointAt(text, index)
        val cc = Character.charCount(cp)
        if (cp < 0x80) {
            out.write(cp)
            return cc
        }
        val sub = text.substring(index, index + cc)
        val gbk = sub.toByteArray(WqxTextCodec.GBK_CHARSET)
        if (gbk.size == 2 && (gbk[0].toInt() and 0x80) != 0) {
            out.write(GBK_ESCAPE_PREFIX)
            out.write(gbk[0].toInt() and 0xFF)
            out.write(gbk[1].toInt() and 0xFF)
        } else {
            out.write(gbk)
        }
        return cc
    }
}
