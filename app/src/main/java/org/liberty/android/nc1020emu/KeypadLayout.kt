package org.liberty.android.nc1020emu

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlin.math.min

class KeypadLayout : LinearLayout {

    private enum class KeyStyle {
        ORANGE_CENTER,
        ORANGE_ENTER,
        RESET_PIN,
        LAVENDER_FN,
        LETTER_SUB,
        SPLIT_NUM,
        SPLIT_SYMBOL_ZERO,
        SPLIT_DOT,
        PAGE_UP_TAX,
        UP_MINUS,
        PAGE_DOWN_MMINUS,
        HELP_RED,
        CENTER_TEXT,
        ESC_AC,
        SPACE_EQ,
        LEFT_ARROW,
        DOWN_PLUS,
        RIGHT_MPLUS
    }

    private data class KeySpec(
        val keyCode: Int,
        val style: KeyStyle,
        val topLegend: String = "",
        val topRightAlign: Boolean = false,
        val mainText: String = "",
        val subText: String = "",
        val subIsRed: Boolean = false
    )

    private val keyMatrix: Array<Array<KeySpec>> = arrayOf(
        // Row 0: Voice, Time, Reset, and 7 Lavender Function Keys
        arrayOf(
            KeySpec(0x15, KeyStyle.ORANGE_CENTER, mainText = "发音"),
            KeySpec(0x14, KeyStyle.ORANGE_CENTER, mainText = "报时"),
            KeySpec(KEYCODE_RESET, KeyStyle.RESET_PIN, mainText = "RESET"),
            KeySpec(0x0B, KeyStyle.LAVENDER_FN, topLegend = "汉 英", mainText = "英汉"),
            KeySpec(0x0C, KeyStyle.LAVENDER_FN, topLegend = "通 讯", mainText = "名片"),
            KeySpec(0x0D, KeyStyle.LAVENDER_FN, topLegend = "换 算", mainText = "计算"),
            KeySpec(0x0A, KeyStyle.LAVENDER_FN, topLegend = "资 料", mainText = "行程"),
            KeySpec(0x09, KeyStyle.LAVENDER_FN, topLegend = "游 戏", mainText = "测验"),
            KeySpec(0x08, KeyStyle.LAVENDER_FN, topLegend = "其 他", mainText = "时间"),
            KeySpec(0x0E, KeyStyle.LAVENDER_FN, mainText = "网络")
        ),
        // Row 1: Q..P + 7 8 9
        arrayOf(
            KeySpec(0x20, KeyStyle.LETTER_SUB, topLegend = "sin⁻¹", mainText = "Q", subText = "sin"),
            KeySpec(0x21, KeyStyle.LETTER_SUB, topLegend = "cos⁻¹", mainText = "W", subText = "cos"),
            KeySpec(0x22, KeyStyle.LETTER_SUB, topLegend = "tan⁻¹", mainText = "E", subText = "tan"),
            KeySpec(0x23, KeyStyle.LETTER_SUB, topLegend = "hyp", mainText = "R", subText = "1/x"),
            KeySpec(0x24, KeyStyle.SPLIT_NUM, mainText = "T", subText = "7"),
            KeySpec(0x25, KeyStyle.SPLIT_NUM, mainText = "Y", subText = "8"),
            KeySpec(0x26, KeyStyle.SPLIT_NUM, mainText = "U", subText = "9"),
            KeySpec(0x27, KeyStyle.LETTER_SUB, mainText = "I", subText = "%"),
            KeySpec(0x18, KeyStyle.LETTER_SUB, topLegend = "#", topRightAlign = true, mainText = "O", subText = "÷"),
            KeySpec(0x1C, KeyStyle.LETTER_SUB, topLegend = TOP_ICON_PHONE, topRightAlign = true, mainText = "P", subText = "MC")
        ),
        // Row 2: A..L + 4 5 6 + Enter
        arrayOf(
            KeySpec(0x28, KeyStyle.LETTER_SUB, topLegend = "10ˣ", mainText = "A", subText = "log"),
            KeySpec(0x29, KeyStyle.LETTER_SUB, topLegend = "eˣ", mainText = "S", subText = "ln"),
            KeySpec(0x2A, KeyStyle.LETTER_SUB, topLegend = "ʸ√x", mainText = "D", subText = "xʸ"),
            KeySpec(0x2B, KeyStyle.LETTER_SUB, topLegend = "x²", mainText = "F", subText = "√"),
            KeySpec(0x2C, KeyStyle.SPLIT_NUM, mainText = "G", subText = "4"),
            KeySpec(0x2D, KeyStyle.SPLIT_NUM, mainText = "H", subText = "5"),
            KeySpec(0x2E, KeyStyle.SPLIT_NUM, mainText = "J", subText = "6"),
            KeySpec(0x2F, KeyStyle.LETTER_SUB, topLegend = "?", topRightAlign = true, mainText = "K", subText = "±"),
            KeySpec(0x19, KeyStyle.LETTER_SUB, topLegend = "*", topRightAlign = true, mainText = "L", subText = "×"),
            KeySpec(0x1D, KeyStyle.ORANGE_ENTER, mainText = "输入", subText = "MR")
        ),
        // Row 3: Z..M + 1 2 3 + PgUp / Up / PgDn
        arrayOf(
            KeySpec(0x30, KeyStyle.LETTER_SUB, topLegend = ")", topRightAlign = true, mainText = "Z", subText = "("),
            KeySpec(0x31, KeyStyle.LETTER_SUB, topLegend = "X!", mainText = "X", subText = "π"),
            KeySpec(0x32, KeyStyle.LETTER_SUB, topLegend = "° ' \"", mainText = "C", subText = "EXP"),
            KeySpec(0x33, KeyStyle.LETTER_SUB, mainText = "V", subText = "C", subIsRed = true),
            KeySpec(0x34, KeyStyle.SPLIT_NUM, mainText = "B", subText = "1"),
            KeySpec(0x35, KeyStyle.SPLIT_NUM, mainText = "N", subText = "2"),
            KeySpec(0x36, KeyStyle.SPLIT_NUM, mainText = "M", subText = "3"),
            KeySpec(0x37, KeyStyle.PAGE_UP_TAX, mainText = "税"),
            KeySpec(0x1A, KeyStyle.UP_MINUS, subText = "−"),
            KeySpec(0x1E, KeyStyle.PAGE_DOWN_MMINUS, subText = "M−")
        ),
        // Row 4: Help, Shift, Caps, Esc, 0, Dot, Space, Left, Down, Right
        arrayOf(
            KeySpec(0x38, KeyStyle.HELP_RED, mainText = "求助"),
            KeySpec(0x39, KeyStyle.CENTER_TEXT, topLegend = "SHIFT", mainText = "中英数"),
            KeySpec(0x3A, KeyStyle.CENTER_TEXT, topLegend = "CAPS", mainText = "输入法"),
            KeySpec(0x3B, KeyStyle.ESC_AC, mainText = "跳出", subText = "AC"),
            KeySpec(0x3C, KeyStyle.SPLIT_SYMBOL_ZERO, topLegend = "继 续", mainText = "符号", subText = "0"),
            KeySpec(0x3D, KeyStyle.SPLIT_DOT, topLegend = "—", topRightAlign = true),
            KeySpec(0x3E, KeyStyle.SPACE_EQ, topLegend = "✓", mainText = "空格", subText = "="),
            KeySpec(0x3F, KeyStyle.LEFT_ARROW),
            KeySpec(0x1B, KeyStyle.DOWN_PLUS, subText = "+"),
            KeySpec(0x1F, KeyStyle.RIGHT_MPLUS, subText = "M+")
        )
    )

    private inner class SharedKeyPalette(ctx: Context) {
        val stdTop = ContextCompat.getColor(ctx, R.color.wqx_key_std_top)
        val stdBot = ContextCompat.getColor(ctx, R.color.wqx_key_std_bot)
        val stdText = ContextCompat.getColor(ctx, R.color.wqx_key_std_text)
        val subBlue = ContextCompat.getColor(ctx, R.color.wqx_key_sub_blue)
        val subRed = ContextCompat.getColor(ctx, R.color.wqx_key_sub_red)
        val numBlueTop = ContextCompat.getColor(ctx, R.color.wqx_key_num_blue_top)
        val numBlueBot = ContextCompat.getColor(ctx, R.color.wqx_key_num_blue_bot)
        val numText = ContextCompat.getColor(ctx, R.color.wqx_key_num_text)
        val orangeTop = ContextCompat.getColor(ctx, R.color.wqx_key_orange_top)
        val orangeBot = ContextCompat.getColor(ctx, R.color.wqx_key_orange_bot)
        val lavTop = ContextCompat.getColor(ctx, R.color.wqx_key_lavender_top)
        val lavBot = ContextCompat.getColor(ctx, R.color.wqx_key_lavender_bot)
        val border = ContextCompat.getColor(ctx, R.color.wqx_key_border)
        val shadow = ContextCompat.getColor(ctx, R.color.wqx_key_shadow)
        val resetPin = ContextCompat.getColor(ctx, R.color.wqx_reset_pin)
        val pressOverlay = ContextCompat.getColor(ctx, R.color.keypad_button_press)
    }

    private inner class WqxKeyView(
        context: Context,
        private val row: Int,
        private val spec: KeySpec,
        private val palette: SharedKeyPalette
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val capRect = RectF()
        private val shadowRect = RectF()
        private val rightHalfRect = RectF()
        private val capPath = Path()
        private val triPath = Path()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blueHalfPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.shadow
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.15f * density
            color = palette.border
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
            color = 0x55FFFFFF
        }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.pressOverlay
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
        }
        private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private var cachedW = 0
        private var cachedH = 0
        private var baseCapTop = 0f
        private var baseCapBottom = 0f
        private var capLeft = 0f
        private var capRight = 0f
        private var cornerR = 0f

        init {
            isClickable = false
            contentDescription = if (spec.mainText.isNotEmpty()) spec.mainText else spec.topLegend
        }

        override fun setPressed(pressed: Boolean) {
            val changed = isPressed != pressed
            super.setPressed(pressed)
            if (changed) {
                invalidate()
            }
        }

        private fun updateMetrics(w: Int, h: Int) {
            if (w == cachedW && h == cachedH) return
            cachedW = w
            cachedH = h
            val wf = w.toFloat()
            val hf = h.toFloat()
            val hPad = (wf * 0.065f).coerceIn(1.6f * density, 3.2f * density)
            capLeft = hPad
            capRight = wf - hPad
            val topLegendZone = if (row == 0) hf * 0.36f else hf * 0.25f
            baseCapTop = topLegendZone
            baseCapBottom = hf - (1.8f * density).coerceAtMost(hf * 0.06f)
            cornerR = (min(capRight - capLeft, baseCapBottom - baseCapTop) * 0.19f)
                .coerceIn(3f * density, 6f * density)

            val (topC, botC) = when (spec.style) {
                KeyStyle.ORANGE_CENTER, KeyStyle.ORANGE_ENTER -> palette.orangeTop to palette.orangeBot
                KeyStyle.LAVENDER_FN -> palette.lavTop to palette.lavBot
                else -> palette.stdTop to palette.stdBot
            }
            fillPaint.shader = LinearGradient(
                0f, baseCapTop, 0f, baseCapBottom,
                topC, botC, Shader.TileMode.CLAMP
            )
            blueHalfPaint.shader = LinearGradient(
                0f, baseCapTop, 0f, baseCapBottom,
                palette.numBlueTop, palette.numBlueBot, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            updateMetrics(w, h)

            val wf = w.toFloat()
            val hf = h.toFloat()

            // Top red deck legend above keycap
            if (spec.topLegend == TOP_ICON_PHONE) {
                drawPhoneLegend(canvas, capRight - 1.5f * density, baseCapTop)
            } else if (spec.topLegend.isNotEmpty()) {
                textPaint.color = palette.subRed
                textPaint.isFakeBoldText = true
                textPaint.textSize = (baseCapTop * 0.66f).coerceIn(7f * density, 10.5f * density)
                val legendY = baseCapTop - (2.2f * density).coerceAtMost(baseCapTop * 0.18f)
                if (spec.topRightAlign) {
                    textPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(spec.topLegend, capRight - 1f * density, legendY, textPaint)
                } else {
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(spec.topLegend, wf * 0.5f, legendY, textPaint)
                }
            }

            if (spec.style == KeyStyle.RESET_PIN) {
                drawResetPin(canvas, wf, hf)
                return
            }

            val pressShift = if (isPressed) 1.1f * density else 0f
            capRect.set(capLeft, baseCapTop + pressShift, capRight, baseCapBottom + pressShift * 0.4f)

            if (!isPressed) {
                shadowRect.set(
                    capRect.left + 0.4f * density,
                    capRect.top + 1.4f * density,
                    capRect.right + 0.4f * density,
                    capRect.bottom + 1.5f * density
                )
                canvas.drawRoundRect(shadowRect, cornerR, cornerR, shadowPaint)
            }

            capPath.reset()
            capPath.addRoundRect(capRect, cornerR, cornerR, Path.Direction.CW)
            canvas.drawPath(capPath, fillPaint)

            if (spec.style == KeyStyle.SPLIT_NUM ||
                spec.style == KeyStyle.SPLIT_SYMBOL_ZERO ||
                spec.style == KeyStyle.SPLIT_DOT
            ) {
                val saveCount = canvas.save()
                canvas.clipPath(capPath)
                rightHalfRect.set(capRect.centerX(), capRect.top, capRect.right, capRect.bottom)
                canvas.drawRect(rightHalfRect, blueHalfPaint)
                canvas.restoreToCount(saveCount)
            }

            if (!isPressed) {
                canvas.drawLine(
                    capRect.left + cornerR * 0.7f,
                    capRect.top + 1f * density,
                    capRect.right - cornerR * 0.7f,
                    capRect.top + 1f * density,
                    highlightPaint
                )
            }
            canvas.drawPath(capPath, borderPaint)

            drawKeyLegends(canvas, capRect)

            if (isPressed) {
                canvas.drawPath(capPath, pressPaint)
            }
        }

        private fun drawPhoneLegend(canvas: Canvas, rightEdge: Float, capTop: Float) {
            val iconH = (capTop * 0.56f).coerceIn(5.5f * density, 8.5f * density)
            val iconW = iconH * 1.32f
            val cx = rightEdge - iconW * 0.5f
            val bottomY = capTop - (1.8f * density).coerceAtMost(capTop * 0.16f)
            val topY = bottomY - iconH

            // Monochrome red silkscreen telephone icon (handset arch + base trapezoid)
            shapePaint.color = palette.subRed
            val earR = iconH * 0.18f
            canvas.drawCircle(cx - iconW * 0.36f, topY + iconH * 0.24f, earR, shapePaint)
            canvas.drawCircle(cx + iconW * 0.36f, topY + iconH * 0.24f, earR, shapePaint)
            shadowRect.set(
                cx - iconW * 0.38f,
                topY + iconH * 0.04f,
                cx + iconW * 0.38f,
                topY + iconH * 0.24f
            )
            canvas.drawRoundRect(shadowRect, earR, earR, shapePaint)

            triPath.reset()
            triPath.moveTo(cx - iconW * 0.22f, topY + iconH * 0.40f)
            triPath.lineTo(cx + iconW * 0.22f, topY + iconH * 0.40f)
            triPath.lineTo(cx + iconW * 0.38f, bottomY)
            triPath.lineTo(cx - iconW * 0.38f, bottomY)
            triPath.close()
            canvas.drawPath(triPath, shapePaint)
        }

        private fun drawResetPin(canvas: Canvas, wf: Float, hf: Float) {
            textPaint.color = palette.stdText
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = (hf * 0.19f).coerceIn(7f * density, 10f * density)
            val labelY = baseCapTop + (baseCapBottom - baseCapTop) * 0.34f
            canvas.drawText(spec.mainText, wf * 0.5f, labelY, textPaint)

            val pinCx = wf * 0.5f
            val pinCy = baseCapTop + (baseCapBottom - baseCapTop) * 0.70f
            val pinR = (min(wf, baseCapBottom - baseCapTop) * 0.16f).coerceIn(3.2f * density, 5.2f * density)
            shapePaint.color = palette.shadow
            canvas.drawCircle(pinCx, pinCy, pinR + 1.2f * density, shapePaint)
            shapePaint.color = palette.border
            canvas.drawCircle(pinCx, pinCy, pinR + 0.5f * density, shapePaint)
            shapePaint.color = palette.resetPin
            canvas.drawCircle(pinCx, pinCy, pinR * (if (isPressed) 0.85f else 1f), shapePaint)
        }

        private fun drawKeyLegends(canvas: Canvas, r: RectF) {
            val cw = r.width()
            val ch = r.height()
            val cx = r.centerX()
            val cy = r.centerY()

            when (spec.style) {
                KeyStyle.ORANGE_CENTER, KeyStyle.LAVENDER_FN -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.37f, ch * 0.50f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.ORANGE_ENTER -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.34f, ch * 0.36f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(r.top + ch * 0.37f, textPaint), textPaint)
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.28f, ch * 0.28f)
                    canvas.drawText(spec.subText, r.right - cw * 0.12f, centeredBaseline(r.top + ch * 0.75f, textPaint), textPaint)
                }
                KeyStyle.LETTER_SUB -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.textSize = min(cw * 0.42f, ch * 0.54f)
                    val mainBase = centeredBaseline(cy, textPaint)
                    canvas.drawText(spec.mainText, r.left + cw * 0.11f, mainBase, textPaint)

                    if (spec.subText.isNotEmpty()) {
                        textPaint.color = if (spec.subIsRed) palette.subRed else palette.subBlue
                        textPaint.textAlign = Paint.Align.RIGHT
                        val subScale = if (spec.subText.length >= 3) 0.25f else 0.30f
                        textPaint.textSize = min(cw * subScale, ch * 0.36f)
                        canvas.drawText(spec.subText, r.right - cw * 0.09f, mainBase, textPaint)
                    }
                }
                KeyStyle.SPLIT_NUM -> {
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.38f, ch * 0.52f)
                    val base = centeredBaseline(cy, textPaint)
                    textPaint.color = palette.stdText
                    canvas.drawText(spec.mainText, r.left + cw * 0.26f, base, textPaint)
                    textPaint.color = palette.numText
                    canvas.drawText(spec.subText, r.left + cw * 0.74f, base, textPaint)
                }
                KeyStyle.SPLIT_SYMBOL_ZERO -> {
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.color = palette.stdText
                    textPaint.textSize = min(cw * 0.28f, ch * 0.32f)
                    canvas.drawText("符", r.left + cw * 0.25f, centeredBaseline(r.top + ch * 0.33f, textPaint), textPaint)
                    canvas.drawText("号", r.left + cw * 0.25f, centeredBaseline(r.top + ch * 0.71f, textPaint), textPaint)

                    textPaint.color = palette.numText
                    textPaint.textSize = min(cw * 0.38f, ch * 0.52f)
                    canvas.drawText("0", r.left + cw * 0.74f, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.SPLIT_DOT -> {
                    val dotR = (min(cw, ch) * 0.075f).coerceAtLeast(1.8f * density)
                    shapePaint.color = palette.stdText
                    canvas.drawCircle(r.left + cw * 0.26f, r.top + ch * 0.70f, dotR, shapePaint)
                    shapePaint.color = palette.numText
                    canvas.drawCircle(r.left + cw * 0.74f, cy, dotR, shapePaint)
                }
                KeyStyle.PAGE_UP_TAX -> {
                    val triCx = r.left + cw * 0.25f
                    val triHalfW = min(cw * 0.12f, ch * 0.14f)
                    val triH = ch * 0.18f
                    drawTriangle(canvas, triCx, r.top + ch * 0.34f, triHalfW, triH, dir = DIR_UP, palette.subRed)
                    drawTriangle(canvas, triCx, r.top + ch * 0.62f, triHalfW, triH, dir = DIR_UP, palette.subRed)

                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.36f, ch * 0.44f)
                    canvas.drawText("税", r.left + cw * 0.68f, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.UP_MINUS -> {
                    val triHalfW = min(cw * 0.14f, ch * 0.17f)
                    drawTriangle(canvas, r.left + cw * 0.28f, cy, triHalfW, ch * 0.28f, dir = DIR_UP, palette.subRed)
                    textPaint.color = palette.subBlue
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.38f, ch * 0.48f)
                    canvas.drawText(spec.subText, r.left + cw * 0.72f, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.PAGE_DOWN_MMINUS -> {
                    val triCx = r.left + cw * 0.23f
                    val triHalfW = min(cw * 0.11f, ch * 0.13f)
                    val triH = ch * 0.18f
                    drawTriangle(canvas, triCx, r.top + ch * 0.36f, triHalfW, triH, dir = DIR_DOWN, palette.subRed)
                    drawTriangle(canvas, triCx, r.top + ch * 0.64f, triHalfW, triH, dir = DIR_DOWN, palette.subRed)

                    textPaint.color = palette.subBlue
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.29f, ch * 0.34f)
                    canvas.drawText(spec.subText, r.right - cw * 0.09f, centeredBaseline(r.top + ch * 0.66f, textPaint), textPaint)
                }
                KeyStyle.HELP_RED -> {
                    textPaint.color = palette.subRed
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.35f, ch * 0.44f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.CENTER_TEXT -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.27f, ch * 0.38f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(cy, textPaint), textPaint)
                }
                KeyStyle.ESC_AC -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.32f, ch * 0.34f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(r.top + ch * 0.36f, textPaint), textPaint)

                    textPaint.color = palette.subRed
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.28f, ch * 0.28f)
                    canvas.drawText(spec.subText, r.right - cw * 0.12f, centeredBaseline(r.top + ch * 0.75f, textPaint), textPaint)
                }
                KeyStyle.SPACE_EQ -> {
                    textPaint.color = palette.stdText
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = min(cw * 0.32f, ch * 0.34f)
                    canvas.drawText(spec.mainText, cx, centeredBaseline(r.top + ch * 0.36f, textPaint), textPaint)

                    textPaint.color = palette.subBlue
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.34f, ch * 0.34f)
                    canvas.drawText(spec.subText, r.right - cw * 0.14f, centeredBaseline(r.top + ch * 0.75f, textPaint), textPaint)
                }
                KeyStyle.LEFT_ARROW -> {
                    val halfH = min(cw * 0.15f, ch * 0.18f)
                    drawTriangle(canvas, r.left + cw * 0.32f, cy, halfH, cw * 0.24f, dir = DIR_LEFT, palette.subRed)
                }
                KeyStyle.DOWN_PLUS -> {
                    val triHalfW = min(cw * 0.14f, ch * 0.17f)
                    drawTriangle(canvas, r.left + cw * 0.28f, r.top + ch * 0.44f, triHalfW, ch * 0.28f, dir = DIR_DOWN, palette.subRed)
                    textPaint.color = palette.subBlue
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.38f, ch * 0.44f)
                    canvas.drawText(spec.subText, r.right - cw * 0.12f, centeredBaseline(r.top + ch * 0.68f, textPaint), textPaint)
                }
                KeyStyle.RIGHT_MPLUS -> {
                    val halfH = min(cw * 0.14f, ch * 0.16f)
                    drawTriangle(canvas, r.left + cw * 0.30f, r.top + ch * 0.42f, halfH, cw * 0.22f, dir = DIR_RIGHT, palette.subRed)
                    textPaint.color = palette.subBlue
                    textPaint.isFakeBoldText = true
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = min(cw * 0.29f, ch * 0.32f)
                    canvas.drawText(spec.subText, r.right - cw * 0.09f, centeredBaseline(r.top + ch * 0.74f, textPaint), textPaint)
                }
                KeyStyle.RESET_PIN -> Unit
            }
        }

        private fun centeredBaseline(centerY: Float, paint: Paint): Float {
            val fm = paint.fontMetrics
            return centerY - (fm.ascent + fm.descent) * 0.5f
        }

        private fun drawTriangle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            halfSpan: Float,
            length: Float,
            dir: Int,
            color: Int
        ) {
            shapePaint.color = color
            triPath.reset()
            when (dir) {
                DIR_UP -> {
                    triPath.moveTo(cx, cy - length * 0.5f)
                    triPath.lineTo(cx - halfSpan, cy + length * 0.5f)
                    triPath.lineTo(cx + halfSpan, cy + length * 0.5f)
                }
                DIR_DOWN -> {
                    triPath.moveTo(cx, cy + length * 0.5f)
                    triPath.lineTo(cx - halfSpan, cy - length * 0.5f)
                    triPath.lineTo(cx + halfSpan, cy - length * 0.5f)
                }
                DIR_LEFT -> {
                    triPath.moveTo(cx - length * 0.5f, cy)
                    triPath.lineTo(cx + length * 0.5f, cy - halfSpan)
                    triPath.lineTo(cx + length * 0.5f, cy + halfSpan)
                }
                DIR_RIGHT -> {
                    triPath.moveTo(cx + length * 0.5f, cy)
                    triPath.lineTo(cx - length * 0.5f, cy - halfSpan)
                    triPath.lineTo(cx - length * 0.5f, cy + halfSpan)
                }
            }
            triPath.close()
            canvas.drawPath(triPath, shapePaint)
        }
    }

    private var onButtonPressedListener: OnButtonPressedListener? = null
    private lateinit var mainKeypadLayout: LinearLayout
    private var defaultMainKeypadHeight = 0
    private val buttonTouchHandlers = ArrayList<ButtonTouchHandler>(55)

    private inner class ButtonTouchHandler(
        private val view: View,
        private val keyCode: Int
    ) : OnTouchListener {
        private var isHolding = false
        private var holdScheduled = false
        private val holdRunnable = Runnable {
            holdScheduled = false
            if (view.isPressed && !isHolding && keyCode >= 0) {
                isHolding = true
                onButtonPressedListener?.onKeyHoldStart(keyCode)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (keyCode == -1 || onButtonPressedListener == null) {
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHoldTimer()
                    if (isHolding) {
                        isHolding = false
                        onButtonPressedListener?.onKeyHoldEnd(keyCode)
                    }
                    v.drawableHotspotChanged(event.x, event.y)
                    v.isPressed = true
                    onButtonPressedListener?.onButtonDown()
                    if (keyCode >= 0) {
                        holdScheduled = true
                        v.postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    v.drawableHotspotChanged(event.x, event.y)
                    if (v.isPressed && !isHolding) {
                        val x = event.x
                        val y = event.y
                        if (x < 0f || x > v.width || y < 0f || y > v.height) {
                            cancelHoldTimer()
                            v.isPressed = false
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelHoldTimer()
                    if (isHolding) {
                        isHolding = false
                        v.isPressed = false
                        onButtonPressedListener?.onKeyHoldEnd(keyCode)
                    } else if (v.isPressed) {
                        v.isPressed = false
                        if (keyCode == KEYCODE_RESET) {
                            onButtonPressedListener?.onResetReleased()
                        } else {
                            onButtonPressedListener?.onKeyReleased(keyCode)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancel(notifyHoldEnd = true)
                }
            }
            return true
        }

        fun cancel(notifyHoldEnd: Boolean) {
            cancelHoldTimer()
            val wasHolding = isHolding
            isHolding = false
            if (view.isPressed) {
                view.isPressed = false
            }
            if (wasHolding && notifyHoldEnd) {
                onButtonPressedListener?.onKeyHoldEnd(keyCode)
            }
        }

        private fun cancelHoldTimer() {
            if (holdScheduled) {
                holdScheduled = false
                view.removeCallbacks(holdRunnable)
            }
        }
    }

    private fun attachButtonTouchHandler(view: View, keyCode: Int) {
        view.tag = keyCode
        val handler = ButtonTouchHandler(view, keyCode)
        buttonTouchHandlers.add(handler)
        view.setOnTouchListener(handler)
    }

    /**
     * Binds upper-lid hardware buttons (F1..F4 in the screen bezel and ON/OFF on the chin)
     * to the same release/500ms-hold touch state machine as the 5x10 main matrix.
     */
    fun bindExternalKeyButton(view: View, keyCode: Int) {
        attachButtonTouchHandler(view, keyCode)
    }

    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    fun init() {
        buttonTouchHandlers.clear()
        orientation = VERTICAL
        defaultMainKeypadHeight = resources.getDimensionPixelSize(R.dimen.main_keypad_height)
        val palette = SharedKeyPalette(context)

        mainKeypadLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, defaultMainKeypadHeight)
            setPadding(dp(1f), dp(2f), dp(1f), 0)
        }

        for (r in 0..4) {
            val rowWeight = if (r == 0) 0.76f else 1.06f
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, rowWeight)
            }
            for (c in 0..9) {
                val spec = keyMatrix[r][c]
                val keyView = WqxKeyView(context, r, spec, palette).apply {
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                }
                attachButtonTouchHandler(keyView, spec.keyCode)
                rowLayout.addView(keyView)
            }
            mainKeypadLayout.addView(rowLayout)
        }

        addView(mainKeypadLayout)
    }

    override fun onDetachedFromWindow() {
        cancelAllTouches(notifyHoldEnd = true)
        super.onDetachedFromWindow()
    }

    fun cancelAllTouches(notifyHoldEnd: Boolean = true) {
        for (handler in buttonTouchHandlers) {
            handler.cancel(notifyHoldEnd)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val availH = (heightSize - paddingTop - paddingBottom).coerceAtLeast(1)
        val targetMainHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> availH
            MeasureSpec.AT_MOST -> minOf(defaultMainKeypadHeight, availH)
            else -> defaultMainKeypadHeight
        }
        if (mainKeypadLayout.layoutParams.height != targetMainHeight) {
            mainKeypadLayout.layoutParams.height = targetMainHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setOnButtonTouchListener(onButtonPressedListener: OnButtonPressedListener?) {
        this.onButtonPressedListener = onButtonPressedListener
    }

    interface OnButtonPressedListener {
        fun onButtonDown()
        fun onKeyReleased(keyCode: Int)
        fun onKeyHoldStart(keyCode: Int)
        fun onKeyHoldEnd(keyCode: Int)
        fun onResetReleased()
    }

    companion object {
        private const val KEYCODE_RESET = -2
        private const val TOP_ICON_PHONE = "__PHONE__"
        const val HOLD_THRESHOLD_MS = 500L
        private const val DIR_UP = 0
        private const val DIR_DOWN = 1
        private const val DIR_LEFT = 2
        private const val DIR_RIGHT = 3
    }
}
