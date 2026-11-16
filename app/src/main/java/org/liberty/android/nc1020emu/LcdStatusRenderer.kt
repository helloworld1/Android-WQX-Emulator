package org.liberty.android.nc1020emu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the WQX NC1020 LCD panel:
 * - Left status strip (22 dot-units wide) + Right status strip (8 dot-units wide): 80 hardware
 *   LCD segments driven by column 0 (bit 7 of lcd_buffer[20 * y] for y = 0..79).
 * - Center 159x80 dot-matrix pixel area (columns 1..159), with optional subtle hardware pixel grid.
 *
 * Static elements (LCD green background, subtle unlit "ghost" segments, and empty-cell grid lines)
 * are pre-rendered into a cached Bitmap whenever surface size or visual settings change, keeping
 * the 60 FPS frame loop allocation-free.
 */
class LcdStatusRenderer(private val lcdBackgroundColor: Int) {

    private val basePaths: Array<Path> = Array(SEGMENT_COUNT) { Path() }
    private val scaledPaths: Array<Path> = Array(SEGMENT_COUNT) { Path() }

    private val activePixelPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
        isFilterBitmap = false
    }

    private val activeSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val ghostSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x20, 0x00, 0x00, 0x00)
        style = Paint.Style.FILL
    }

    private val bgGridPaint = Paint().apply {
        color = Color.argb(0x16, 0x00, 0x00, 0x00)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = false
    }

    private val activeGridOverlayPaint = Paint().apply {
        color = (lcdBackgroundColor and 0x00FFFFFF) or 0x36000000
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = false
    }

    private val bitmapMatrix = Matrix()
    private var staticBgBitmap: Bitmap? = null
    private var gridLines: FloatArray = FloatArray(0)
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedShowStatus = true
    private var cachedShowGrid = true
    private var dotScale = 1f

    init {
        buildBasePaths()
    }

    fun draw(
        canvas: Canvas,
        surfaceWidth: Int,
        surfaceHeight: Int,
        lcdBitmap: Bitmap,
        segments: ByteArray,
        showStatus: Boolean,
        showPixelGrid: Boolean
    ) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        ensurePrepared(surfaceWidth, surfaceHeight, showStatus, showPixelGrid)

        val bg = staticBgBitmap
        if (bg != null) {
            canvas.drawBitmap(bg, 0f, 0f, null)
        } else {
            canvas.drawColor(lcdBackgroundColor)
        }

        canvas.drawBitmap(lcdBitmap, bitmapMatrix, activePixelPaint)

        if (showPixelGrid && dotScale >= 2.5f && gridLines.isNotEmpty()) {
            canvas.drawLines(gridLines, activeGridOverlayPaint)
        }

        if (showStatus) {
            for (y in 0 until SEGMENT_COUNT) {
                if (segments[y].toInt() != 0) {
                    canvas.drawPath(scaledPaths[y], activeSegmentPaint)
                }
            }
        }
    }

    private fun ensurePrepared(
        width: Int,
        height: Int,
        showStatus: Boolean,
        showGrid: Boolean
    ) {
        if (width == cachedWidth &&
            height == cachedHeight &&
            showStatus == cachedShowStatus &&
            showGrid == cachedShowGrid &&
            staticBgBitmap != null
        ) {
            return
        }

        cachedWidth = width
        cachedHeight = height
        cachedShowStatus = showStatus
        cachedShowGrid = showGrid

        val totalUnitsX = if (showStatus) TOTAL_UNITS_WITH_STATUS else MATRIX_COLS.toFloat()
        val scaleX = width.toFloat() / totalUnitsX
        val scaleY = height.toFloat() / MATRIX_ROWS.toFloat()
        dotScale = minOf(scaleX, scaleY)

        val matrixOffsetX = if (showStatus) LEFT_STRIP_UNITS * scaleX else 0f
        bitmapMatrix.reset()
        bitmapMatrix.postScale(scaleX, scaleY)
        bitmapMatrix.postTranslate(matrixOffsetX, 0f)

        if (showStatus) {
            val pathMatrix = Matrix().apply {
                setScale(scaleX / BASE_MUL, scaleY / BASE_MUL)
            }
            for (i in 0 until SEGMENT_COUNT) {
                basePaths[i].transform(pathMatrix, scaledPaths[i])
            }
        }

        gridLines = if (showGrid) {
            buildGridLines(matrixOffsetX, scaleX, scaleY, width, height)
        } else {
            FloatArray(0)
        }

        staticBgBitmap?.recycle()
        val bg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(bg)
        bgCanvas.drawColor(lcdBackgroundColor)

        if (showGrid && gridLines.isNotEmpty()) {
            bgCanvas.drawLines(gridLines, bgGridPaint)
        }

        if (showStatus) {
            for (i in 0 until SEGMENT_COUNT) {
                bgCanvas.drawPath(scaledPaths[i], ghostSegmentPaint)
            }
        }

        staticBgBitmap = bg
    }

    private fun buildGridLines(
        offsetX: Float,
        stepX: Float,
        stepY: Float,
        totalWidth: Int,
        totalHeight: Int
    ): FloatArray {
        val vCount = MATRIX_COLS + 1
        val hCount = MATRIX_ROWS + 1
        val pts = FloatArray((vCount + hCount) * 4)
        var idx = 0
        val leftEdge = kotlin.math.round(offsetX).toInt().coerceIn(0, totalWidth - 1).toFloat()
        val rightEdge = kotlin.math.round(offsetX + MATRIX_COLS * stepX)
            .toInt().coerceIn(0, totalWidth - 1).toFloat() + 0.5f
        val bottomEdge = (totalHeight - 1).coerceAtLeast(0).toFloat() + 0.5f
        for (col in 0..MATRIX_COLS) {
            val x = kotlin.math.round(offsetX + col * stepX)
                .toInt().coerceIn(0, totalWidth - 1).toFloat() + 0.5f
            pts[idx++] = x
            pts[idx++] = 0f
            pts[idx++] = x
            pts[idx++] = bottomEdge
        }
        for (row in 0..MATRIX_ROWS) {
            val y = kotlin.math.round(row * stepY)
                .toInt().coerceIn(0, totalHeight - 1).toFloat() + 0.5f
            pts[idx++] = leftEdge
            pts[idx++] = y
            pts[idx++] = rightEdge
            pts[idx++] = y
        }
        return pts
    }

    /**
     * Build all 80 segment vector paths in 10x logical LCD coordinates (1890 x 800) so Skia
     * stroke-to-fill and font outline extraction operate with full curve precision.
     */
    private fun buildBasePaths() {
        // 1. Top-left 4x 7-segment digits
        //    Digit 0 ($03FB): f=0, a=1, b=2, g=3, c=33, d=34, e=35
        //    Digit 1 ($03FC): f=5, a=6, b=7, g=8, c=29, d=30, e=31
        //    Digit 2 ($03FD): f=10, a=11, b=13, g=14, c=24, d=25, e=26
        //    Digit 3 ($03FE): f=15, a=16, b=17, g=18, c=19, d=21, e=22
        buildSevenSegDigit(1.1f, 1.1f, a = 1, b = 2, c = 33, d = 34, e = 35, f = 0, g = 3)
        buildSevenSegDigit(5.3f, 1.1f, a = 6, b = 7, c = 29, d = 30, e = 31, f = 5, g = 8)
        buildSevenSegDigit(9.5f, 1.1f, a = 11, b = 13, c = 24, d = 25, e = 26, f = 10, g = 14)
        buildSevenSegDigit(13.7f, 1.1f, a = 16, b = 17, c = 19, d = 21, e = 22, f = 15, g = 18)

        // Colon (y=9, between Digit 1 & Digit 2) and 3 decimal points (y=32, 27, 23)
        addCircle(basePaths[9], 8.65f, 3.25f, 0.34f)
        addCircle(basePaths[9], 8.65f, 5.55f, 0.34f)
        addCircle(basePaths[32], 4.45f, 8.25f, 0.34f)
        addCircle(basePaths[27], 8.65f, 8.25f, 0.34f)
        addCircle(basePaths[23], 12.85f, 8.25f, 0.34f)

        // 2. Far-left vertical scrollbar
        //    Outer frame (y=65), Up arrow (y=79), Down arrow (y=66),
        //    9 tick-groups x 5 ticks = 45 horizontal ticks (y=43, 45, 56, 78, 77, 57, 76, 75, 73)
        buildScrollbarFrame(basePaths[65])
        buildVerticalArrow(basePaths[79], cx = 2.9f, top = 10.4f, bottom = 14.3f, pointUp = true)
        buildVerticalArrow(basePaths[66], cx = 2.9f, top = 70.1f, bottom = 74.0f, pointUp = false)

        val tickGroupRows = intArrayOf(43, 45, 56, 78, 77, 57, 76, 75, 73)
        val firstTickY = 16.0f
        val tickStepY = 1.19f
        for (group in tickGroupRows.indices) {
            val path = basePaths[tickGroupRows[group]]
            for (t in 0 until 5) {
                val idx = group * 5 + t
                val cy = firstTickY + idx * tickStepY
                addRect(path, 1.70f, cy - 0.24f, 4.10f, cy + 0.24f)
            }
        }

        // 3. Left badges & status icons
        buildDoubleVerticalTriangles(basePaths[38], cx = 8.4f, top = 10.4f, bottom = 13.7f, pointUp = true)
        buildStar(basePaths[37], cx = 13.0f, cy = 12.2f, rOuter = 1.15f, rInner = 0.46f)

        buildBadge(basePaths[39], "NUM", left = 6.6f, top = 14.5f, right = 16.4f, bottom = 18.2f)
        buildBadge(basePaths[40], "ENG", left = 6.6f, top = 19.3f, right = 16.4f, bottom = 23.0f)
        buildBadge(basePaths[41], "CAPS", left = 6.6f, top = 24.1f, right = 16.4f, bottom = 27.8f)
        buildBadge(basePaths[42], "SHIFT", left = 6.6f, top = 28.9f, right = 16.4f, bottom = 32.6f)

        buildBell(basePaths[47], cx = 8.6f, top = 33.4f, bottom = 37.1f)
        buildLightning(basePaths[46], left = 13.0f, top = 33.3f, right = 16.2f, bottom = 37.1f)

        buildWaveIcon(basePaths[59], cx = 8.9f, cy = 40.8f, clockCenter = false)
        buildMusicNote(basePaths[48], left = 13.4f, top = 38.1f, right = 16.4f, bottom = 42.8f)

        buildWaveIcon(basePaths[51], cx = 8.9f, cy = 45.8f, clockCenter = true)
        buildSpeaker(basePaths[50], left = 12.3f, top = 44.0f, right = 16.4f, bottom = 47.5f)

        buildGlasses(basePaths[53], left = 6.6f, cy = 50.8f, right = 11.4f)
        buildMicrophone(basePaths[54], left = 12.0f, top = 48.9f, right = 16.1f, bottom = 53.6f)

        // 7-block horizontal bar (y=55)
        for (i in 0 until 7) {
            val x0 = 12.0f + i * 1.15f
            addRect(basePaths[55], x0, 56.4f, x0 + 0.92f, 58.3f)
        }

        buildBattery(basePaths[58], left = 7.1f, top = 59.3f, right = 10.3f, bottom = 63.6f)
        buildKey(basePaths[49], left = 11.9f, cy = 61.5f, right = 16.5f)

        buildDoubleHorizontalTriangles(basePaths[61], left = 6.8f, cy = 66.5f, right = 10.4f, pointLeft = true)
        buildDoubleHorizontalTriangles(basePaths[62], left = 12.6f, cy = 66.5f, right = 16.2f, pointLeft = false)

        buildDoubleVerticalTriangles(basePaths[64], cx = 8.4f, top = 70.5f, bottom = 73.8f, pointUp = false)
        buildHorizontalArrow(basePaths[63], left = 12.4f, cy = 71.8f, right = 16.2f, pointLeft = true)

        // 4. Bottom horizontal slider gauge (y=72 bracket, y=67 left tri, y=69 ticks, y=71 right tri)
        buildBottomGaugeBracket(basePaths[72])
        buildSingleHorizontalTriangle(basePaths[67], left = 1.4f, cy = 78.2f, right = 3.0f, pointLeft = true)
        for (i in 0 until 9) {
            val x0 = 3.95f + i * 1.02f
            addRect(basePaths[69], x0, 77.0f, x0 + 0.34f, 79.3f)
        }
        buildSingleHorizontalTriangle(basePaths[71], left = 13.6f, cy = 78.2f, right = 15.2f, pointLeft = false)

        // 5. Right status strip: 10 pentagon tab pointers + right arrow (y=70)
        val rightTabRows = intArrayOf(4, 12, 20, 28, 36, 44, 52, 60, 68, 74)
        val rightTabCentersY = floatArrayOf(
            5.0f, 12.9f, 20.8f, 28.7f, 36.3f, 44.2f, 51.9f, 59.9f, 67.6f, 75.6f
        )
        for (i in rightTabRows.indices) {
            buildRightPentagonTab(basePaths[rightTabRows[i]], cy = rightTabCentersY[i])
        }
        buildHorizontalArrow(basePaths[70], left = 184.5f, cy = 71.8f, right = 188.3f, pointLeft = false)
    }

    private fun buildSevenSegDigit(
        x: Float,
        y: Float,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
        f: Int,
        g: Int
    ) {
        val w = 2.65f
        val h = 6.45f
        val t = 0.56f
        val gap = 0.11f
        val midY = y + h * 0.5f

        // a (top horizontal)
        addPoly(
            basePaths[a],
            x + gap * 2f, y,
            x + w - gap * 2f, y,
            x + w - t - gap, y + t,
            x + t + gap, y + t
        )
        // d (bottom horizontal)
        addPoly(
            basePaths[d],
            x + t + gap, y + h - t,
            x + w - t - gap, y + h - t,
            x + w - gap * 2f, y + h,
            x + gap * 2f, y + h
        )
        // g (middle horizontal hexagon)
        val ht = t * 0.5f
        addPoly(
            basePaths[g],
            x + ht + gap * 1.5f, midY,
            x + t + gap, midY - ht,
            x + w - t - gap, midY - ht,
            x + w - ht - gap * 1.5f, midY,
            x + w - t - gap, midY + ht,
            x + t + gap, midY + ht
        )
        // f (top-left vertical)
        addPoly(
            basePaths[f],
            x, y + gap * 2f,
            x + t, y + t + gap,
            x + t, midY - ht - gap,
            x + ht * 0.6f, midY - gap,
            x, midY - ht
        )
        // b (top-right vertical)
        addPoly(
            basePaths[b],
            x + w, y + gap * 2f,
            x + w, midY - ht,
            x + w - ht * 0.6f, midY - gap,
            x + w - t, midY - ht - gap,
            x + w - t, y + t + gap
        )
        // e (bottom-left vertical)
        addPoly(
            basePaths[e],
            x, midY + ht,
            x + ht * 0.6f, midY + gap,
            x + t, midY + ht + gap,
            x + t, y + h - t - gap,
            x, y + h - gap * 2f
        )
        // c (bottom-right vertical)
        addPoly(
            basePaths[c],
            x + w - ht * 0.6f, midY + gap,
            x + w, midY + ht,
            x + w, y + h - gap * 2f,
            x + w - t, y + h - t - gap,
            x + w - t, midY + ht + gap
        )
    }

    private fun buildScrollbarFrame(dst: Path) {
        val left = 0.65f
        val right = 5.15f
        val top = 9.7f
        val bottom = 74.8f
        val st = 0.36f

        // Left wall + top wall + top-right wall down to trace break 1 (y=27.6)
        addRect(dst, left, top, left + st, bottom)
        addRect(dst, left, top, right, top + st)
        addRect(dst, right - st, top, right, 27.6f)
        // Middle-right wall segment between ITO trace breaks (y=28.4..53.2)
        addRect(dst, right - st, 28.4f, right, 53.2f)
        // Lower-right wall segment (y=54.0..bottom)
        addRect(dst, right - st, 54.0f, right, bottom)
        // Bottom wall with small center ITO trace gap
        addRect(dst, left, bottom - st, 2.55f, bottom)
        addRect(dst, 3.25f, bottom - st, right, bottom)
        // Inner horizontal dividers under [UP] and above [DOWN]
        addRect(dst, left, 14.85f, right, 14.85f + st)
        addRect(dst, left, 69.3f, right, 69.3f + st)
        normalizeUnion(dst)
    }

    private fun buildVerticalArrow(
        dst: Path,
        cx: Float,
        top: Float,
        bottom: Float,
        pointUp: Boolean
    ) {
        val halfHeadW = 1.35f
        val halfShaftW = 0.48f
        val midY = (top + bottom) * 0.5f
        if (pointUp) {
            addPoly(
                dst,
                cx, top,
                cx + halfHeadW, midY + 0.15f,
                cx + halfShaftW, midY + 0.15f,
                cx + halfShaftW, bottom,
                cx - halfShaftW, bottom,
                cx - halfShaftW, midY + 0.15f,
                cx - halfHeadW, midY + 0.15f
            )
        } else {
            addPoly(
                dst,
                cx - halfShaftW, top,
                cx + halfShaftW, top,
                cx + halfShaftW, midY - 0.15f,
                cx + halfHeadW, midY - 0.15f,
                cx, bottom,
                cx - halfHeadW, midY - 0.15f,
                cx - halfShaftW, midY - 0.15f
            )
        }
    }

    private fun buildHorizontalArrow(
        dst: Path,
        left: Float,
        cy: Float,
        right: Float,
        pointLeft: Boolean
    ) {
        val halfHeadH = 1.45f
        val halfShaftH = 0.50f
        val midX = (left + right) * 0.5f
        if (pointLeft) {
            addPoly(
                dst,
                left, cy,
                midX + 0.1f, cy - halfHeadH,
                midX + 0.1f, cy - halfShaftH,
                right, cy - halfShaftH,
                right, cy + halfShaftH,
                midX + 0.1f, cy + halfShaftH,
                midX + 0.1f, cy + halfHeadH
            )
        } else {
            addPoly(
                dst,
                left, cy - halfShaftH,
                midX - 0.1f, cy - halfShaftH,
                midX - 0.1f, cy - halfHeadH,
                right, cy,
                midX - 0.1f, cy + halfHeadH,
                midX - 0.1f, cy + halfShaftH,
                left, cy + halfShaftH
            )
        }
    }

    private fun buildDoubleVerticalTriangles(
        dst: Path,
        cx: Float,
        top: Float,
        bottom: Float,
        pointUp: Boolean
    ) {
        val midY = (top + bottom) * 0.5f
        val hw = 1.35f
        if (pointUp) {
            addPoly(dst, cx, top, cx + hw, midY - 0.1f, cx - hw, midY - 0.1f)
            addPoly(dst, cx, midY + 0.1f, cx + hw, bottom, cx - hw, bottom)
        } else {
            addPoly(dst, cx - hw, top, cx + hw, top, cx, midY - 0.1f)
            addPoly(dst, cx - hw, midY + 0.1f, cx + hw, midY + 0.1f, cx, bottom)
        }
    }

    private fun buildDoubleHorizontalTriangles(
        dst: Path,
        left: Float,
        cy: Float,
        right: Float,
        pointLeft: Boolean
    ) {
        val midX = (left + right) * 0.5f
        val hh = 1.30f
        if (pointLeft) {
            addPoly(dst, left, cy, midX - 0.05f, cy - hh, midX - 0.05f, cy + hh)
            addPoly(dst, midX + 0.05f, cy, right, cy - hh, right, cy + hh)
        } else {
            addPoly(dst, left, cy - hh, midX - 0.05f, cy, left, cy + hh)
            addPoly(dst, midX + 0.05f, cy - hh, right, cy, midX + 0.05f, cy + hh)
        }
    }

    private fun buildSingleHorizontalTriangle(
        dst: Path,
        left: Float,
        cy: Float,
        right: Float,
        pointLeft: Boolean
    ) {
        val hh = 1.25f
        if (pointLeft) {
            addPoly(dst, left, cy, right, cy - hh, right, cy + hh)
        } else {
            addPoly(dst, left, cy - hh, right, cy, left, cy + hh)
        }
    }

    private fun buildStar(dst: Path, cx: Float, cy: Float, rOuter: Float, rInner: Float) {
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) rOuter else rInner
            val ang = -Math.PI / 2.0 + i * Math.PI / 5.0
            pts[2 * i] = (cx + r * cos(ang)).toFloat()
            pts[2 * i + 1] = (cy + r * sin(ang)).toFloat()
        }
        addPoly(dst, *pts)
    }

    private fun buildBadge(
        dst: Path,
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val st = 0.32f
        val outer = Path().apply {
            addRoundRect(
                RectF(left * BASE_MUL, top * BASE_MUL, right * BASE_MUL, bottom * BASE_MUL),
                0.65f * BASE_MUL,
                0.65f * BASE_MUL,
                Path.Direction.CW
            )
        }
        val inner = Path().apply {
            addRoundRect(
                RectF(
                    (left + st) * BASE_MUL,
                    (top + st) * BASE_MUL,
                    (right - st) * BASE_MUL,
                    (bottom - st) * BASE_MUL
                ),
                0.40f * BASE_MUL,
                0.40f * BASE_MUL,
                Path.Direction.CW
            )
        }
        outer.op(inner, Path.Op.DIFFERENCE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (if (label.length >= 5) 2.15f else if (label.length == 4) 2.30f else 2.45f) * BASE_MUL
        }
        val rawGlyphs = Path()
        textPaint.getTextPath(label, 0, label.length, 0f, 0f, rawGlyphs)
        val bounds = RectF()
        rawGlyphs.computeBounds(bounds, true)
        val cx = (left + right) * 0.5f * BASE_MUL
        val cy = (top + bottom) * 0.5f * BASE_MUL
        rawGlyphs.offset(cx - bounds.centerX(), cy - bounds.centerY())

        outer.op(rawGlyphs, Path.Op.UNION)
        dst.set(outer)
    }

    private fun buildBell(dst: Path, cx: Float, top: Float, bottom: Float) {
        val p = Path()
        val m = BASE_MUL
        // Top knob
        p.addCircle(cx * m, (top + 0.35f) * m, 0.32f * m, Path.Direction.CW)
        // Bell dome + flared skirt
        val dome = Path().apply {
            moveTo((cx - 1.95f) * m, (bottom - 0.70f) * m)
            quadTo((cx - 1.45f) * m, (bottom - 1.25f) * m, (cx - 1.35f) * m, (top + 1.55f) * m)
            cubicTo(
                (cx - 1.30f) * m, (top + 0.45f) * m,
                (cx + 1.30f) * m, (top + 0.45f) * m,
                (cx + 1.35f) * m, (top + 1.55f) * m
            )
            quadTo((cx + 1.45f) * m, (bottom - 1.25f) * m, (cx + 1.95f) * m, (bottom - 0.70f) * m)
            lineTo((cx + 1.95f) * m, (bottom - 0.42f) * m)
            lineTo((cx - 1.95f) * m, (bottom - 0.42f) * m)
            close()
        }
        p.op(dome, Path.Op.UNION)
        // Clapper
        val clapper = Path().apply {
            addCircle(cx * m, (bottom - 0.22f) * m, 0.36f * m, Path.Direction.CW)
        }
        p.op(clapper, Path.Op.UNION)
        dst.set(p)
    }

    private fun buildLightning(
        dst: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val midY = (top + bottom) * 0.5f
        addPoly(
            dst,
            right - 0.2f, top,
            left + 0.1f, midY + 0.25f,
            left + 1.45f, midY + 0.25f,
            left + 0.3f, bottom,
            right - 0.05f, midY - 0.25f,
            right - 1.40f, midY - 0.25f
        )
    }

    private fun buildWaveIcon(dst: Path, cx: Float, cy: Float, clockCenter: Boolean) {
        val union = Path()
        if (clockCenter) {
            addStrokedCircle(union, cx, cy, 0.95f, 0.28f)
            addStrokedLine(union, cx, cy, cx, cy - 0.62f, 0.24f)
            addStrokedLine(union, cx, cy, cx + 0.55f, cy, 0.24f)
        } else {
            addCircle(union, cx, cy, 0.40f)
        }
        // Inner left & right wave arcs
        addStrokedArc(union, cx, cy, radius = 1.55f, startDeg = 142f, sweepDeg = 76f, stroke = 0.28f)
        addStrokedArc(union, cx, cy, radius = 1.55f, startDeg = -38f, sweepDeg = 76f, stroke = 0.28f)
        // Outer left & right wave arcs
        addStrokedArc(union, cx, cy, radius = 2.20f, startDeg = 140f, sweepDeg = 80f, stroke = 0.28f)
        addStrokedArc(union, cx, cy, radius = 2.20f, startDeg = -40f, sweepDeg = 80f, stroke = 0.28f)
        normalizeUnion(union)
        dst.set(union)
    }

    private fun buildMusicNote(
        dst: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val m = BASE_MUL
        val head = Path()
        val oval = RectF(
            left * m,
            (bottom - 1.55f) * m,
            (left + 1.85f) * m,
            bottom * m
        )
        head.addOval(oval, Path.Direction.CW)
        val rot = Matrix().apply {
            setRotate(-25f, oval.centerX(), oval.centerY())
        }
        head.transform(rot)

        val stemX = left + 1.50f
        val stem = Path().apply {
            addRect(
                stemX * m,
                top * m,
                (stemX + 0.36f) * m,
                (bottom - 0.65f) * m,
                Path.Direction.CW
            )
        }
        val flag = Path().apply {
            moveTo((stemX + 0.30f) * m, top * m)
            quadTo((right + 0.15f) * m, (top + 0.85f) * m, (right - 0.15f) * m, (top + 2.05f) * m)
            quadTo((right - 0.35f) * m, (top + 1.15f) * m, (stemX + 0.30f) * m, (top + 0.72f) * m)
            close()
        }
        head.op(stem, Path.Op.UNION)
        head.op(flag, Path.Op.UNION)
        dst.set(head)
    }

    private fun buildSpeaker(
        dst: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val cy = (top + bottom) * 0.5f
        val union = Path()
        val poly = Path()
        addPoly(
            poly,
            left, cy - 0.70f,
            left + 1.05f, cy - 0.70f,
            left + 2.45f, top,
            left + 2.45f, bottom,
            left + 1.05f, cy + 0.70f,
            left, cy + 0.70f
        )
        addStrokedPath(union, poly, 0.30f, closed = true)
        addStrokedLine(union, right - 1.05f, cy - 0.95f, right, cy - 1.45f, 0.26f)
        addStrokedLine(union, right - 1.00f, cy, right + 0.05f, cy, 0.26f)
        addStrokedLine(union, right - 1.05f, cy + 0.95f, right, cy + 1.45f, 0.26f)
        normalizeUnion(union)
        dst.set(union)
    }

    private fun buildGlasses(dst: Path, left: Float, cy: Float, right: Float) {
        val union = Path()
        val r = 0.98f
        val c1x = left + r + 0.15f
        val c2x = right - r - 0.15f
        addStrokedCircle(union, c1x, cy, r, 0.28f)
        addStrokedCircle(union, c2x, cy, r, 0.28f)
        addStrokedLine(union, c1x + r, cy - 0.15f, c2x - r, cy - 0.15f, 0.28f)
        normalizeUnion(union)
        dst.set(union)
    }

    private fun buildMicrophone(
        dst: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val union = Path()
        val headCx = right - 1.05f
        val headCy = top + 1.05f
        addStrokedCircle(union, headCx, headCy, 0.92f, 0.28f)
        // Tapered angled handle toward bottom-left
        val handle = Path()
        addPoly(
            handle,
            headCx - 0.65f, headCy + 0.60f,
            headCx - 0.10f, headCy + 0.90f,
            left + 1.25f, bottom - 0.35f,
            left + 0.90f, bottom - 0.55f
        )
        addStrokedPath(union, handle, 0.26f, closed = true)
        addStrokedLine(union, left + 1.05f, bottom - 0.40f, left, bottom - 0.15f, 0.24f)
        normalizeUnion(union)
        dst.set(union)
    }

    private fun buildBattery(
        dst: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val m = BASE_MUL
        val cx = (left + right) * 0.5f
        val bodyTop = top + 0.50f
        val st = 0.34f

        val p = Path()
        // Positive terminal nub
        p.addRect((cx - 0.65f) * m, top * m, (cx + 0.65f) * m, bodyTop * m, Path.Direction.CW)
        // Outer battery body minus inner cavity
        val body = Path().apply {
            addRect(left * m, bodyTop * m, right * m, bottom * m, Path.Direction.CW)
        }
        val cavity = Path().apply {
            addRect(
                (left + st) * m,
                (bodyTop + st) * m,
                (right - st) * m,
                (bottom - st) * m,
                Path.Direction.CW
            )
        }
        body.op(cavity, Path.Op.DIFFERENCE)
        p.op(body, Path.Op.UNION)

        // Small '+' symbol in upper half
        val plusCy = bodyTop + 1.25f
        val plus = Path().apply {
            addRect((cx - 0.55f) * m, (plusCy - 0.15f) * m, (cx + 0.55f) * m, (plusCy + 0.15f) * m, Path.Direction.CW)
            addRect((cx - 0.15f) * m, (plusCy - 0.55f) * m, (cx + 0.15f) * m, (plusCy + 0.55f) * m, Path.Direction.CW)
        }
        p.op(plus, Path.Op.UNION)

        // Bottom-right diagonal fill wedge matching WQX battery icon
        val wedge = Path()
        addPoly(
            wedge,
            left + st, bottom - st,
            right - st, bottom - 1.45f,
            right - st, bottom - st
        )
        p.op(wedge, Path.Op.UNION)
        dst.set(p)
    }

    private fun buildKey(dst: Path, left: Float, cy: Float, right: Float) {
        val m = BASE_MUL
        val rOuter = 1.20f
        val rInner = 0.52f
        val bowCx = left + rOuter

        val bow = Path().apply {
            addCircle(bowCx * m, cy * m, rOuter * m, Path.Direction.CW)
        }
        val hole = Path().apply {
            addCircle(bowCx * m, cy * m, rInner * m, Path.Direction.CW)
        }
        bow.op(hole, Path.Op.DIFFERENCE)

        val shaft = Path().apply {
            addRect((bowCx + rOuter - 0.2f) * m, (cy - 0.28f) * m, right * m, (cy + 0.22f) * m, Path.Direction.CW)
            addRect((right - 1.15f) * m, (cy + 0.20f) * m, (right - 0.75f) * m, (cy + 0.95f) * m, Path.Direction.CW)
            addRect((right - 0.45f) * m, (cy + 0.20f) * m, (right - 0.05f) * m, (cy + 0.80f) * m, Path.Direction.CW)
        }
        bow.op(shaft, Path.Op.UNION)
        dst.set(bow)
    }

    private fun buildBottomGaugeBracket(dst: Path) {
        val st = 0.34f
        val left = 0.65f
        val right = 16.2f
        val top = 75.8f
        val bottom = 79.6f
        addRect(dst, left, top, left + st, bottom)
        // Top bar with small trace gap on left
        addRect(dst, left, top, 5.2f, top + st)
        addRect(dst, 6.2f, top, right, top + st)
        addRect(dst, right - st, top, right, bottom)
        normalizeUnion(dst)
    }

    private fun buildRightPentagonTab(dst: Path, cy: Float) {
        val left = 184.8f
        val shoulder = 186.9f
        val tip = 188.0f
        val hh = 0.96f
        addPoly(
            dst,
            left, cy - hh,
            shoulder, cy - hh,
            tip, cy,
            shoulder, cy + hh,
            left, cy + hh
        )
    }

    private fun addRect(dst: Path, l: Float, t: Float, r: Float, b: Float) {
        dst.addRect(l * BASE_MUL, t * BASE_MUL, r * BASE_MUL, b * BASE_MUL, Path.Direction.CW)
    }

    private fun addCircle(dst: Path, cx: Float, cy: Float, radius: Float) {
        dst.addCircle(cx * BASE_MUL, cy * BASE_MUL, radius * BASE_MUL, Path.Direction.CW)
    }

    private fun addPoly(dst: Path, vararg coords: Float) {
        if (coords.size < 6) return
        val p = Path()
        p.moveTo(coords[0] * BASE_MUL, coords[1] * BASE_MUL)
        var i = 2
        while (i + 1 < coords.size) {
            p.lineTo(coords[i] * BASE_MUL, coords[i + 1] * BASE_MUL)
            i += 2
        }
        p.close()
        dst.op(p, Path.Op.UNION)
    }

    private fun addStrokedCircle(dst: Path, cx: Float, cy: Float, radius: Float, stroke: Float) {
        val outer = Path().apply {
            addCircle(cx * BASE_MUL, cy * BASE_MUL, (radius + stroke * 0.5f) * BASE_MUL, Path.Direction.CW)
        }
        val inner = Path().apply {
            addCircle(cx * BASE_MUL, cy * BASE_MUL, (radius - stroke * 0.5f) * BASE_MUL, Path.Direction.CW)
        }
        outer.op(inner, Path.Op.DIFFERENCE)
        dst.op(outer, Path.Op.UNION)
    }

    private fun addStrokedArc(
        dst: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        startDeg: Float,
        sweepDeg: Float,
        stroke: Float
    ) {
        val m = BASE_MUL
        val arc = Path().apply {
            addArc(
                RectF((cx - radius) * m, (cy - radius) * m, (cx + radius) * m, (cy + radius) * m),
                startDeg,
                sweepDeg
            )
        }
        addStrokedPath(dst, arc, stroke, closed = false)
    }

    private fun addStrokedLine(
        dst: Path,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        stroke: Float
    ) {
        val line = Path().apply {
            moveTo(x0 * BASE_MUL, y0 * BASE_MUL)
            lineTo(x1 * BASE_MUL, y1 * BASE_MUL)
        }
        addStrokedPath(dst, line, stroke, closed = false)
    }

    private fun addStrokedPath(dst: Path, src: Path, stroke: Float, closed: Boolean) {
        val sp = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * BASE_MUL
            strokeCap = if (closed) Paint.Cap.BUTT else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val filled = Path()
        sp.getFillPath(src, filled)
        dst.op(filled, Path.Op.UNION)
    }

    private fun normalizeUnion(path: Path) {
        val empty = Path()
        path.op(empty, Path.Op.UNION)
    }

    fun release() {
        staticBgBitmap?.recycle()
        staticBgBitmap = null
    }

    companion object {
        const val PREF_SHOW_LCD_STATUS = "show_lcd_status"
        const val PREF_SHOW_PIXEL_GRID = "show_pixel_grid"

        const val SEGMENT_COUNT = 80
        const val MATRIX_COLS = 159
        const val MATRIX_ROWS = 80
        const val LEFT_STRIP_UNITS = 22f
        const val RIGHT_STRIP_UNITS = 8f
        const val TOTAL_UNITS_WITH_STATUS = LEFT_STRIP_UNITS + MATRIX_COLS + RIGHT_STRIP_UNITS // 189f
        private const val BASE_MUL = 10f
    }
}
