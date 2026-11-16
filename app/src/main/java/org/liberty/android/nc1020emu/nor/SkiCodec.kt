package org.liberty.android.nc1020emu.nor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

object SkiCodec {
    const val LCD_WIDTH = 160
    const val LCD_HEIGHT = 80
    const val ROW_BYTES = 20
    const val FRAME_BYTES = ROW_BYTES * LCD_HEIGHT // 1,600 bytes
    const val SCRIPT_HEADER_BYTES = 200            // 0xC8 bytes
    const val STATIC_SKI_BYTES = SCRIPT_HEADER_BYTES + 2 + FRAME_BYTES // 1,802 bytes

    private const val COLOR_LCD_BG = 0xFFA8CE97.toInt()
    private const val COLOR_LCD_LIT = 0xFF1A2218.toInt()

    data class SkiFrame(
        val bitmap: Bitmap,
        val delayMs: Int
    )

    fun decodeSkiFrames(
        bytes: ByteArray,
        bgColor: Int = COLOR_LCD_BG,
        litColor: Int = COLOR_LCD_LIT
    ): List<SkiFrame> {
        if (bytes.isEmpty()) return emptyList()

        // Fallback for raw 1600-byte or 1602-byte 160x80 bitmaps
        if (bytes.size == FRAME_BYTES) {
            return listOf(SkiFrame(renderVramToBitmap(bytes, bgColor, litColor), 1000))
        }
        if (bytes.size == FRAME_BYTES + 2 &&
            (bytes[0].toInt() and 0xFF) == LCD_WIDTH &&
            (bytes[1].toInt() and 0xFF) == LCD_HEIGHT
        ) {
            val vram = bytes.copyOfRange(2, 2 + FRAME_BYTES)
            return listOf(SkiFrame(renderVramToBitmap(vram, bgColor, litColor), 1000))
        }

        if (bytes.size < SCRIPT_HEADER_BYTES) return emptyList()

        val vram = ByteArray(FRAME_BYTES)
        val frames = ArrayList<SkiFrame>()
        var pc = 0
        var loopTarget = 0
        var loopRemain = 0
        var steps = 0

        while (pc in 0 until SCRIPT_HEADER_BYTES && frames.size < 32 && steps < 256) {
            steps++
            val op = bytes[pc].toInt() and 0xFF
            when (op) {
                0xFA -> {
                    if (pc + 7 > SCRIPT_HEADER_BYTES) break
                    val off = (bytes[pc + 1].toInt() and 0xFF) or ((bytes[pc + 2].toInt() and 0xFF) shl 8)
                    val mode = bytes[pc + 3].toInt() and 0xFF
                    val xPx = bytes[pc + 4].toInt() and 0xFF
                    val anim = bytes[pc + 5].toInt() and 0xFF
                    val yPx = bytes[pc + 6].toInt() and 0xFF
                    if (blitSkiSprite(bytes, off, xPx, yPx, mode, vram)) {
                        val delay = if (anim > 0) (anim * 80).coerceIn(80, 2000) else 250
                        frames.add(SkiFrame(renderVramToBitmap(vram, bgColor, litColor), delay))
                    }
                    pc += 7
                }
                0xFB -> {
                    if (pc + 2 > SCRIPT_HEADER_BYTES) break
                    val ticks = bytes[pc + 1].toInt() and 0xFF
                    if (frames.isNotEmpty() && ticks > 0) {
                        val last = frames.removeAt(frames.lastIndex)
                        frames.add(last.copy(delayMs = (last.delayMs + ticks * 60).coerceAtMost(4000)))
                    }
                    pc += 2
                }
                0xFC -> {
                    if (pc + 2 > SCRIPT_HEADER_BYTES) break
                    val arg = bytes[pc + 1].toInt() and 0x7F
                    if (loopRemain == 0 && arg > 1) {
                        loopRemain = minOf(arg - 1, 3)
                        pc = loopTarget
                    } else if (loopRemain > 0) {
                        loopRemain--
                        pc = loopTarget
                    } else {
                        pc += 2
                    }
                }
                0xFD -> {
                    pc += 5
                }
                0xFE -> {
                    loopTarget = pc + 1
                    pc += 1
                }
                0xFF, 0x00 -> break
                else -> break
            }
        }

        // If script didn't match standard opcodes, scan for [0xA0, 0x50] @ +200 fallback
        if (frames.isEmpty() && bytes.size >= STATIC_SKI_BYTES &&
            (bytes[SCRIPT_HEADER_BYTES].toInt() and 0xFF) == LCD_WIDTH &&
            (bytes[SCRIPT_HEADER_BYTES + 1].toInt() and 0xFF) == LCD_HEIGHT
        ) {
            val sub = bytes.copyOfRange(SCRIPT_HEADER_BYTES + 2, SCRIPT_HEADER_BYTES + 2 + FRAME_BYTES)
            frames.add(SkiFrame(renderVramToBitmap(sub, bgColor, litColor), 1000))
        }

        return frames
    }

    private fun blitSkiSprite(
        fileBytes: ByteArray,
        offset: Int,
        xPx: Int,
        yPx: Int,
        mode: Int,
        vram: ByteArray
    ): Boolean {
        if (offset < 0 || offset + 2 > fileBytes.size) return false
        val widthPx = fileBytes[offset].toInt() and 0xFF
        val heightPx = fileBytes[offset + 1].toInt() and 0xFF
        val widthBytes = widthPx ushr 3
        if (widthBytes <= 0 || heightPx <= 0) return false
        val spriteBytes = widthBytes * heightPx
        if (offset + 2 + spriteBytes > fileBytes.size) return false

        val srcBase = offset + 2
        for (sy in 0 until heightPx) {
            val dy = yPx + sy
            if (dy !in 0 until LCD_HEIGHT) continue
            for (sx in 0 until widthBytes * 8) {
                val dx = xPx + sx
                if (dx !in 0 until LCD_WIDTH) continue
                val srcByte = fileBytes[srcBase + sy * widthBytes + (sx ushr 3)].toInt() and 0xFF
                val srcBit = (srcByte ushr (7 - (sx and 7))) and 1
                val dstIdx = dy * ROW_BYTES + (dx ushr 3)
                val dstMask = 1 shl (7 - (dx and 7))
                when (mode) {
                    1 -> if (srcBit != 0) vram[dstIdx] = (vram[dstIdx].toInt() or dstMask).toByte()
                    2 -> if (srcBit != 0) vram[dstIdx] = (vram[dstIdx].toInt() xor dstMask).toByte()
                    else -> {
                        vram[dstIdx] = if (srcBit != 0) {
                            (vram[dstIdx].toInt() or dstMask).toByte()
                        } else {
                            (vram[dstIdx].toInt() and dstMask.inv()).toByte()
                        }
                    }
                }
            }
        }
        return true
    }

    private fun renderVramToBitmap(vram: ByteArray, bgColor: Int, litColor: Int): Bitmap {
        val pixels = IntArray(LCD_WIDTH * LCD_HEIGHT)
        for (y in 0 until LCD_HEIGHT) {
            val rowOff = y * ROW_BYTES
            val pixOff = y * LCD_WIDTH
            for (bx in 0 until ROW_BYTES) {
                val b = vram[rowOff + bx].toInt() and 0xFF
                for (bit in 0 until 8) {
                    val lit = ((b ushr (7 - bit)) and 1) != 0
                    pixels[pixOff + bx * 8 + bit] = if (lit) litColor else bgColor
                }
            }
        }
        return Bitmap.createBitmap(pixels, LCD_WIDTH, LCD_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    fun convertImageToSki(
        source: Bitmap,
        cropCenter: Boolean = true,
        dither: Boolean = true,
        invert: Boolean = false,
        bgColor: Int = COLOR_LCD_BG,
        litColor: Int = COLOR_LCD_LIT
    ): Pair<ByteArray, Bitmap> {
        val scaled = Bitmap.createBitmap(LCD_WIDTH, LCD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.WHITE)

        val srcW = source.width.coerceAtLeast(1).toFloat()
        val srcH = source.height.coerceAtLeast(1).toFloat()
        val scale = if (cropCenter) {
            maxOf(LCD_WIDTH / srcW, LCD_HEIGHT / srcH)
        } else {
            min(LCD_WIDTH / srcW, LCD_HEIGHT / srcH)
        }
        val dstW = srcW * scale
        val dstH = srcH * scale
        val left = (LCD_WIDTH - dstW) * 0.5f
        val top = (LCD_HEIGHT - dstH) * 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + dstW, top + dstH), paint)

        val argb = IntArray(LCD_WIDTH * LCD_HEIGHT)
        scaled.getPixels(argb, 0, LCD_WIDTH, 0, 0, LCD_WIDTH, LCD_HEIGHT)
        scaled.recycle()

        // Luminance buffer [0..255]
        val lum = FloatArray(LCD_WIDTH * LCD_HEIGHT)
        for (i in argb.indices) {
            val c = argb[i]
            val a = ((c ushr 24) and 0xFF) / 255f
            val r = (((c ushr 16) and 0xFF) * a + 255f * (1f - a))
            val g = (((c ushr 8) and 0xFF) * a + 255f * (1f - a))
            val b = ((c and 0xFF) * a + 255f * (1f - a))
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            lum[i] = if (invert) 255f - l else l
        }

        if (dither) {
            for (y in 0 until LCD_HEIGHT) {
                for (x in 0 until LCD_WIDTH) {
                    val idx = y * LCD_WIDTH + x
                    val oldVal = lum[idx].coerceIn(0f, 255f)
                    val newVal = if (oldVal < 128f) 0f else 255f
                    lum[idx] = newVal
                    val err = oldVal - newVal
                    if (x + 1 < LCD_WIDTH) lum[idx + 1] += err * (7f / 16f)
                    if (y + 1 < LCD_HEIGHT) {
                        if (x > 0) lum[idx + LCD_WIDTH - 1] += err * (3f / 16f)
                        lum[idx + LCD_WIDTH] += err * (5f / 16f)
                        if (x + 1 < LCD_WIDTH) lum[idx + LCD_WIDTH + 1] += err * (1f / 16f)
                    }
                }
            }
        }

        val vram = ByteArray(FRAME_BYTES)
        for (y in 0 until LCD_HEIGHT) {
            for (x in 1 until LCD_WIDTH) { // Keep x=0 unlit so left/right status segments stay clean
                val dark = lum[y * LCD_WIDTH + x] < 128f
                if (dark) {
                    val byteIdx = y * ROW_BYTES + (x ushr 3)
                    vram[byteIdx] = (vram[byteIdx].toInt() or (1 shl (7 - (x and 7)))).toByte()
                }
            }
        }

        val skiBytes = ByteArray(STATIC_SKI_BYTES)
        // Script: 0xFA (draw @ 0x00C8, mode=0, x=0, anim=0, y=0), 0xFB 0x10 (delay), 0xFF (end)
        skiBytes[0] = 0xFA.toByte()
        skiBytes[1] = (SCRIPT_HEADER_BYTES and 0xFF).toByte()
        skiBytes[2] = ((SCRIPT_HEADER_BYTES ushr 8) and 0xFF).toByte()
        skiBytes[3] = 0x00
        skiBytes[4] = 0x00
        skiBytes[5] = 0x00
        skiBytes[6] = 0x00
        skiBytes[7] = 0xFB.toByte()
        skiBytes[8] = 0x10
        skiBytes[9] = 0xFF.toByte()

        skiBytes[SCRIPT_HEADER_BYTES] = LCD_WIDTH.toByte()
        skiBytes[SCRIPT_HEADER_BYTES + 1] = LCD_HEIGHT.toByte()
        System.arraycopy(vram, 0, skiBytes, SCRIPT_HEADER_BYTES + 2, FRAME_BYTES)

        val previewBitmap = renderVramToBitmap(vram, bgColor, litColor)
        return skiBytes to previewBitmap
    }

    fun upscaleCrisp(bitmap: Bitmap, scale: Int = 4): Bitmap =
        Bitmap.createScaledBitmap(bitmap, bitmap.width * scale, bitmap.height * scale, false)
}
