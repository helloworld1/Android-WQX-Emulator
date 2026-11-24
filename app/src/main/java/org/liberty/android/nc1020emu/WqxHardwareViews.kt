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
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Root clamshell container.
 * In Portrait (VERTICAL), sizes the upper lid and lower deck to natural NC1020 proportions
 * (with KeypadLayout capped at 1:1 square main buttons) and top-aligns the open clamshell
 * so any leftover height on tall phones remains cleanly blank at the bottom.
 */
class WqxClamshellContainerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var isFullLcdMode = false
    private var showLcdStatus = true

    fun configureSkin(isFullLcd: Boolean, showStatus: Boolean) {
        if (this.isFullLcdMode != isFullLcd || this.showLcdStatus != showStatus) {
            this.isFullLcdMode = isFullLcd
            this.showLcdStatus = showStatus
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (orientation != VERTICAL || childCount < 3 || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val totalW = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = MeasureSpec.getSize(heightMeasureSpec)
        val innerW = (totalW - paddingLeft - paddingRight).coerceAtLeast(1)
        val innerH = (totalH - paddingTop - paddingBottom).coerceAtLeast(1)

        val upperLid = getChildAt(0)
        val hinge = getChildAt(1)
        val lowerDeck = getChildAt(2)

        val upperLp = upperLid.layoutParams as LayoutParams
        val hingeLp = hinge.layoutParams as LayoutParams
        val lowerLp = lowerDeck.layoutParams as LayoutParams

        measureChildWithMargins(hinge, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val hingeTotalH = hinge.measuredHeight + hingeLp.topMargin + hingeLp.bottomMargin

        val upperW = (innerW - upperLp.leftMargin - upperLp.rightMargin).coerceAtLeast(1)
        val lowerW = (innerW - lowerLp.leftMargin - lowerLp.rightMargin).coerceAtLeast(1)
        val availForHalves = (
            innerH - hingeTotalH - upperLp.topMargin - upperLp.bottomMargin -
                lowerLp.topMargin - lowerLp.bottomMargin
        ).coerceAtLeast(2)

        val idealUpperRatio = when {
            !isFullLcdMode -> 0.76f
            showLcdStatus -> 0.675f
            else -> 0.75f
        }
        val idealUpperH = (upperW * idealUpperRatio).roundToInt().coerceAtLeast(1)
        // Reserve at least 46% for upper lid when vertically constrained, up to idealUpperH on tall screens
        val initialUpperH = min(idealUpperH, (availForHalves * 0.46f).roundToInt().coerceAtLeast(1))
        val maxLowerH = (availForHalves - initialUpperH).coerceAtLeast(1)

        lowerDeck.measure(
            MeasureSpec.makeMeasureSpec(lowerW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxLowerH, MeasureSpec.AT_MOST)
        )
        val finalUpperH = min(idealUpperH, (availForHalves - lowerDeck.measuredHeight).coerceAtLeast(1))
        upperLid.measure(
            MeasureSpec.makeMeasureSpec(upperW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalUpperH, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(
            resolveSize(totalW, widthMeasureSpec),
            resolveSize(totalH, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (orientation != VERTICAL || childCount < 3) {
            super.onLayout(changed, l, t, r, b)
            return
        }

        val innerLeft = paddingLeft
        val innerRight = (r - l) - paddingRight
        val availW = (innerRight - innerLeft).coerceAtLeast(0)
        var curY = paddingTop

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val childLeft = innerLeft + lp.leftMargin +
                ((availW - lp.leftMargin - lp.rightMargin - cw).coerceAtLeast(0) / 2)
            curY += lp.topMargin
            child.layout(childLeft, curY, childLeft + cw, curY + ch)
            curY += ch + lp.bottomMargin
        }
    }
}

/**
 * Lower clamshell keyboard base containing:
 * - Original skin: WqxSpeakerGrilleView + bounded-aspect KeypadLayout + bottom latch View
 * - Full LCD skin: brushed-silver [Speaker + F1..F4 + ON/OFF] bar + KeypadLayout + bottom latch View
 */
class WqxLowerDeckLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    var isFullLcdMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val topBar = findViewById<View>(if (isFullLcdMode) R.id.wqx_deck_fn_bar else R.id.wqx_speaker_grille)
        val keypad = findViewById<View>(R.id.keypad_layout)
        val latch = findViewById<View>(R.id.wqx_chin_latch)
        if (topBar == null || keypad == null || latch == null || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val totalW = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val innerW = (totalW - paddingLeft - paddingRight).coerceAtLeast(1)
        val innerH = (totalH - paddingTop - paddingBottom).coerceAtLeast(1)

        val speakerLp = topBar.layoutParams as LayoutParams
        val keypadLp = keypad.layoutParams as LayoutParams
        val latchLp = latch.layoutParams as LayoutParams

        measureChildWithMargins(latch, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val latchTotalH = latch.measuredHeight + latchLp.topMargin + latchLp.bottomMargin

        val minSpeakerH = (38f * density).roundToInt()
        val idealSpeakerH = if (isFullLcdMode) {
            resources.getDimensionPixelSize(R.dimen.deck_fn_bar_height).coerceAtLeast(minSpeakerH)
        } else {
            (innerW * if (heightMode == MeasureSpec.EXACTLY) 0.145f else 0.185f)
                .roundToInt()
                .coerceAtLeast(minSpeakerH)
        }

        val nonSpeakerChrome = latchTotalH + speakerLp.topMargin + speakerLp.bottomMargin +
            keypadLp.topMargin + keypadLp.bottomMargin
        val keypadAvailW = (innerW - keypadLp.leftMargin - keypadLp.rightMargin).coerceAtLeast(1)

        // Give top bar its ideal height if there is room, shrinking down to minSpeakerH on short screens
        val maxKeypadWithIdealSpeaker = (innerH - nonSpeakerChrome - idealSpeakerH).coerceAtLeast(1)
        val maxKeypadWithMinSpeaker = (innerH - nonSpeakerChrome - minSpeakerH).coerceAtLeast(1)
        keypad.measure(
            MeasureSpec.makeMeasureSpec(keypadAvailW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxKeypadWithIdealSpeaker, MeasureSpec.AT_MOST)
        )
        if (keypad.measuredWidth < keypadAvailW && maxKeypadWithMinSpeaker > maxKeypadWithIdealSpeaker) {
            // Screen is vertically tight enough that keypad hit its 1.21:1 floor; shrink top bar first
            keypad.measure(
                MeasureSpec.makeMeasureSpec(keypadAvailW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxKeypadWithMinSpeaker, MeasureSpec.AT_MOST)
            )
        }
        val keypadTotalH = keypad.measuredHeight + keypadLp.topMargin + keypadLp.bottomMargin

        val remainingForSpeaker = (
            innerH - keypadTotalH - latchTotalH - speakerLp.topMargin - speakerLp.bottomMargin
        ).coerceAtLeast(1)
        val maxTopBarH = if (isFullLcdMode) {
            (idealSpeakerH * 1.08f).roundToInt()
        } else if (heightMode == MeasureSpec.EXACTLY) {
            (innerW * 0.22f).roundToInt().coerceAtLeast(idealSpeakerH)
        } else {
            idealSpeakerH
        }
        val targetSpeakerH = remainingForSpeaker.coerceIn(
            min(minSpeakerH, remainingForSpeaker),
            maxTopBarH
        )

        val speakerAvailW = (innerW - speakerLp.leftMargin - speakerLp.rightMargin).coerceAtLeast(1)
        topBar.measure(
            MeasureSpec.makeMeasureSpec(speakerAvailW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetSpeakerH, MeasureSpec.EXACTLY)
        )
        val speakerTotalH = topBar.measuredHeight + speakerLp.topMargin + speakerLp.bottomMargin

        val contentH = paddingTop + speakerTotalH + keypadTotalH + latchTotalH + paddingBottom
        val measuredH = if (heightMode == MeasureSpec.EXACTLY) totalH else min(totalH, contentH)
        setMeasuredDimension(resolveSize(totalW, widthMeasureSpec), measuredH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val innerLeft = paddingLeft
        val innerRight = (r - l) - paddingRight
        val innerTop = paddingTop
        val innerBottom = (b - t) - paddingBottom
        val availW = (innerRight - innerLeft).coerceAtLeast(0)
        val availH = (innerBottom - innerTop).coerceAtLeast(0)

        var contentH = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            contentH += child.measuredHeight + lp.topMargin + lp.bottomMargin
        }

        var curY = innerTop + ((availH - contentH).coerceAtLeast(0) / 2)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val childLeft = innerLeft + lp.leftMargin +
                ((availW - lp.leftMargin - lp.rightMargin - cw).coerceAtLeast(0) / 2)
            curY += lp.topMargin
            child.layout(childLeft, curY, childLeft + cw, curY + ch)
            curY += ch + lp.bottomMargin
        }
    }
}

/**
 * Upper clamshell lid container modeled on the WQX NC1020 industrial design:
 * royal-cobalt-blue outer shell rim, pearl silver-white inner faceplate, and
 * top-center recessed blue U-collar housing a 3D silver-white latch clasp.
 */
class WqxUpperLidLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val collarRect = RectF()
    private val claspSlotRect = RectF()
    private val claspTabRect = RectF()
    private val outerPath = Path()
    private val innerPath = Path()
    private val outerRadii = FloatArray(8)
    private val innerRadii = FloatArray(8)

    private val blueRimStart = ContextCompat.getColor(context, R.color.wqx_blue_rim_start)
    private val blueRimEnd = ContextCompat.getColor(context, R.color.wqx_blue_rim_end)
    private val blueRimHighlight = ContextCompat.getColor(context, R.color.wqx_blue_rim_highlight)
    private val faceplateStart = ContextCompat.getColor(context, R.color.wqx_faceplate_start)
    private val faceplateEnd = ContextCompat.getColor(context, R.color.wqx_faceplate_end)
    private val faceplateStroke = ContextCompat.getColor(context, R.color.wqx_faceplate_stroke)
    private val claspHi = ContextCompat.getColor(context, R.color.wqx_hinge_white_hi)
    private val claspFill = ContextCompat.getColor(context, R.color.wqx_top_clasp_fill)
    private val claspStroke = ContextCompat.getColor(context, R.color.wqx_top_clasp_stroke)
    private val seamDark = ContextCompat.getColor(context, R.color.wqx_key_border)

    private val rimFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = blueRimHighlight
    }
    private val plateFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = faceplateStroke
    }
    private val collarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val claspSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = seamDark
    }
    private val claspTabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val claspRidgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = claspStroke
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val halfStroke = 0.5f * density
        outerRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val topR = 18f * density
        val botR = 7f * density
        setCornerRadii(outerRadii, topR, botR)
        outerPath.reset()
        outerPath.addRoundRect(outerRect, outerRadii, Path.Direction.CW)

        val rimSide = 5.5f * density
        val rimTop = 6f * density
        val rimBot = 5f * density
        innerRect.set(rimSide, rimTop, w - rimSide, h - rimBot)
        setCornerRadii(innerRadii, 13f * density, 4f * density)
        innerPath.reset()
        innerPath.addRoundRect(innerRect, innerRadii, Path.Direction.CW)

        rimFillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            blueRimStart, blueRimEnd, Shader.TileMode.CLAMP
        )
        plateFillPaint.shader = LinearGradient(
            0f, innerRect.top, 0f, innerRect.bottom,
            faceplateStart, faceplateEnd, Shader.TileMode.CLAMP
        )

        val cx = w * 0.5f
        val collarHalfW = 20f * density
        val collarBottom = 10.5f * density
        collarRect.set(cx - collarHalfW, halfStroke, cx + collarHalfW, collarBottom)
        collarFillPaint.shader = LinearGradient(
            0f, 0f, 0f, collarBottom,
            blueRimStart, blueRimEnd, Shader.TileMode.CLAMP
        )

        val slotHalfW = 13.5f * density
        claspSlotRect.set(cx - slotHalfW, 0.8f * density, cx + slotHalfW, 8.2f * density)
        val tabHalfW = 12.2f * density
        claspTabRect.set(cx - tabHalfW, 0.8f * density, cx + tabHalfW, 7.4f * density)
        claspTabPaint.shader = LinearGradient(
            0f, claspTabRect.top, 0f, claspTabRect.bottom,
            intArrayOf(claspHi, claspFill, claspStroke),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun setCornerRadii(arr: FloatArray, topRadius: Float, bottomRadius: Float) {
        arr[0] = topRadius
        arr[1] = topRadius
        arr[2] = topRadius
        arr[3] = topRadius
        arr[4] = bottomRadius
        arr[5] = bottomRadius
        arr[6] = bottomRadius
        arr[7] = bottomRadius
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.drawPath(outerPath, rimFillPaint)
        canvas.drawPath(outerPath, rimStrokePaint)

        canvas.drawPath(innerPath, plateFillPaint)
        canvas.drawPath(innerPath, plateStrokePaint)

        val collarRadius = 5f * density
        canvas.drawRoundRect(collarRect, collarRadius, collarRadius, collarFillPaint)
        canvas.drawRoundRect(claspSlotRect, 2.2f * density, 2.2f * density, claspSlotPaint)
        canvas.drawRoundRect(claspTabRect, 1.8f * density, 1.8f * density, claspTabPaint)
        val ridgeY = claspTabRect.top + claspTabRect.height() * 0.55f
        canvas.drawLine(
            claspTabRect.left + 3f * density,
            ridgeY,
            claspTabRect.right - 3f * density,
            ridgeY,
            claspRidgePaint
        )
    }
}

/**
 * Draws the NC1020 lower-left chin emblem: calligraphic "文曲星®" + dark navy "真人发音"
 * pill badge + curved 7-dot acoustic sound wave arc to the right of the pill.
 */
class WqxBrandEmblemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val brushColor = ContextCompat.getColor(context, R.color.wqx_logo_brush)
    private val badgeBgColor = ContextCompat.getColor(context, R.color.wqx_voice_badge_bg)
    private val badgeTextColor = ContextCompat.getColor(context, R.color.wqx_voice_badge_text)

    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColor
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSkewX = -0.10f
        isFakeBoldText = true
    }
    private val regPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColor
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = true
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = badgeBgColor
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badgeTextColor
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = badgeBgColor
    }
    private val pillRect = RectF()
    private val dotAnglesDeg = floatArrayOf(-58f, -29f, 0f, 29f, 58f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (156f * density).toInt()
        val desiredH = (32f * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scale = min(w / (156f * density), h / (32f * density)).coerceIn(0.65f, 1.25f)
        logoPaint.textSize = 21f * density * scale
        logoPaint.letterSpacing = 0.06f
        regPaint.textSize = 7.5f * density * scale
        badgeTextPaint.textSize = 9.2f * density * scale
        badgeTextPaint.letterSpacing = 0.08f

        val logoText = "文曲星"
        val logoBaseline = h * 0.77f
        val logoX = 1f * density
        canvas.drawText(logoText, logoX, logoBaseline, logoPaint)

        val logoW = logoPaint.measureText(logoText)
        val regX = logoX + logoW + 1f * density
        val regY = logoBaseline - 11.5f * density * scale
        canvas.drawText("®", regX, regY, regPaint)

        val pillLeft = regX + 5.5f * density * scale
        val pillH = 13.5f * density * scale
        val pillW = 54f * density * scale
        val pillBottom = logoBaseline + 1.5f * density * scale
        val pillTop = pillBottom - pillH
        pillRect.set(pillLeft, pillTop, pillLeft + pillW, pillBottom)
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, badgeBgPaint)

        val textFm = badgeTextPaint.fontMetrics
        val badgeTextY = pillRect.centerY() - (textFm.ascent + textFm.descent) * 0.5f
        canvas.drawText("真人发音", pillRect.centerX(), badgeTextY, badgeTextPaint)

        // Curved 5-dot sound wave arc wrapping around the right cap of the pill
        val arcCenterX = pillRect.right - pillH * 0.34f
        val arcCenterY = pillRect.centerY()
        val arcRadius = pillH * 0.80f
        val dotRadius = 1.3f * density * scale
        for (deg in dotAnglesDeg) {
            val rad = Math.toRadians(deg.toDouble())
            val dx = arcCenterX + (arcRadius * cos(rad)).toFloat()
            val dy = arcCenterY + (arcRadius * sin(rad)).toFloat()
            canvas.drawCircle(dx, dy, dotRadius, dotPaint)
        }
    }
}

/**
 * Draws the recessed circular sky-blue ON/OFF power button with curved "ON/OFF"
 * perimeter lettering along the lower silver bezel ring, matching the NC1020 render.
 */
class WqxOnOffButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val outerWellColor = ContextCompat.getColor(context, R.color.wqx_onoff_outer_well)
    private val ringColor = ContextCompat.getColor(context, R.color.wqx_onoff_ring)
    private val capTopColor = ContextCompat.getColor(context, R.color.wqx_onoff_top)
    private val capBotColor = ContextCompat.getColor(context, R.color.wqx_onoff_bottom)
    private val capPressedTop = ContextCompat.getColor(context, R.color.wqx_onoff_pressed_top)
    private val capPressedBot = ContextCompat.getColor(context, R.color.wqx_onoff_pressed_bottom)
    private val labelColor = ContextCompat.getColor(context, R.color.wqx_fn_label_text)

    private val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ringColor
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x44FFFFFF
    }
    private val arcTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val arcRect = RectF()
    private val textArcPath = Path()

    init {
        contentDescription = "ON/OFF"
    }

    override fun setPressed(pressed: Boolean) {
        val changed = isPressed != pressed
        super.setPressed(pressed)
        if (changed) {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h).toFloat()
        if (size <= 0f) return
        val cx = w * 0.5f
        val cy = h * 0.48f
        val outerR = size * 0.47f
        val capR = outerR * 0.57f

        wellPaint.shader = LinearGradient(
            cx, cy - outerR, cx, cy + outerR,
            darkenColor(outerWellColor, 0.90f),
            lightenColor(outerWellColor, 1.12f),
            Shader.TileMode.CLAMP
        )
        capPaint.shader = LinearGradient(
            cx, cy - capR, cx, cy + capR,
            capTopColor, capBotColor, Shader.TileMode.CLAMP
        )
        capPressedPaint.shader = LinearGradient(
            cx, cy - capR, cx, cy + capR,
            capPressedTop, capPressedBot, Shader.TileMode.CLAMP
        )

        val textR = outerR * 0.84f
        arcRect.set(cx - textR, cy - textR, cx + textR, cy + textR)
        textArcPath.reset()
        // Counter-clockwise lower arc from left-bottom (155°) to right-bottom (25°) so text reads upright
        textArcPath.addArc(arcRect, 158f, -136f)
        arcTextPaint.textSize = (size * 0.155f).coerceAtLeast(6.5f * density)
        arcTextPaint.letterSpacing = 0.06f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width * 0.5f
        val cy = height * 0.48f
        val outerR = size * 0.47f
        val ringR = outerR * 0.66f
        val capR = outerR * 0.57f
        val pressOffset = if (isPressed) 0.7f * density else 0f

        // Outer concave silver well (shifted slightly up so bottom bezel band holds ON/OFF arc)
        canvas.drawCircle(cx, cy, outerR, wellPaint)
        val buttonCy = cy - outerR * 0.08f
        canvas.drawCircle(cx, buttonCy, ringR, ringPaint)
        canvas.drawCircle(
            cx,
            buttonCy + pressOffset,
            capR,
            if (isPressed) capPressedPaint else capPaint
        )
        if (!isPressed) {
            canvas.drawCircle(
                cx - capR * 0.24f,
                buttonCy - capR * 0.26f,
                capR * 0.28f,
                specularPaint
            )
        }
        canvas.drawTextOnPath("ON/OFF", textArcPath, 0f, arcTextPaint.textSize * 0.34f, arcTextPaint)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val a = color ushr 24
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val a = color ushr 24
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Two-tone Pearl-White + Royal-Cobalt-Blue + Brushed-Silver-Collar cylindrical clamshell hinge.
 * Automatically renders horizontally in Portrait (width >= height) and vertically in Landscape.
 */
class WqxHingeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val whiteColors = intArrayOf(
        ContextCompat.getColor(context, R.color.wqx_hinge_white_top),
        ContextCompat.getColor(context, R.color.wqx_hinge_white_hi),
        ContextCompat.getColor(context, R.color.wqx_hinge_white_bot)
    )
    private val blueColors = intArrayOf(
        ContextCompat.getColor(context, R.color.wqx_hinge_blue_top),
        ContextCompat.getColor(context, R.color.wqx_hinge_blue_hi),
        ContextCompat.getColor(context, R.color.wqx_hinge_blue_bot)
    )
    private val collarColors = intArrayOf(
        ContextCompat.getColor(context, R.color.wqx_hinge_collar_top),
        ContextCompat.getColor(context, R.color.wqx_hinge_collar_hi),
        ContextCompat.getColor(context, R.color.wqx_hinge_collar_bot)
    )
    private val stops = floatArrayOf(0f, 0.34f, 1f)

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.wqx_key_border)
    }
    private val segRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val horizontal = w >= h
        val x1 = if (horizontal) 0f else w.toFloat()
        val y1 = if (horizontal) h.toFloat() else 0f
        whitePaint.shader = LinearGradient(0f, 0f, x1, y1, whiteColors, stops, Shader.TileMode.CLAMP)
        bluePaint.shader = LinearGradient(0f, 0f, x1, y1, blueColors, stops, Shader.TileMode.CLAMP)
        collarPaint.shader = LinearGradient(0f, 0f, x1, y1, collarColors, stops, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val horizontal = w >= h
        val length = if (horizontal) w else h
        val thick = if (horizontal) h else w
        val endR = thick * 0.44f
        val knuckleR = thick * 0.34f
        val centerInset = thick * 0.14f

        // Segments matching NC1020 hinge: White cap | Blue knuckle | Silver collar | Center blue bar | Blue knuckle | White cap
        drawSegment(canvas, horizontal, 0f, length * 0.085f, 0f, thick, endR, whitePaint)
        drawSegment(canvas, horizontal, length * 0.085f, length * 0.215f, 0f, thick, knuckleR, bluePaint)
        drawSegment(canvas, horizontal, length * 0.220f, length * 0.355f, thick * 0.06f, thick * 0.94f, endR * 0.4f, collarPaint)
        drawSegment(canvas, horizontal, length * 0.360f, length * 0.790f, centerInset, thick - centerInset, endR * 0.35f, bluePaint)
        drawSegment(canvas, horizontal, length * 0.795f, length * 0.915f, 0f, thick, knuckleR, bluePaint)
        drawSegment(canvas, horizontal, length * 0.915f, length, 0f, thick, endR, whitePaint)

        // Subtle joint grooves between outer caps and blue knuckles
        drawSeam(canvas, horizontal, length * 0.085f, thick)
        drawSeam(canvas, horizontal, length * 0.915f, thick)
    }

    private fun drawSegment(
        canvas: Canvas,
        horizontal: Boolean,
        startAlong: Float,
        endAlong: Float,
        crossStart: Float,
        crossEnd: Float,
        radius: Float,
        paint: Paint
    ) {
        if (horizontal) {
            segRect.set(startAlong, crossStart, endAlong, crossEnd)
        } else {
            segRect.set(crossStart, startAlong, crossEnd, endAlong)
        }
        canvas.drawRoundRect(segRect, radius, radius, paint)
    }

    private fun drawSeam(canvas: Canvas, horizontal: Boolean, pos: Float, thick: Float) {
        if (horizontal) {
            canvas.drawLine(pos, thick * 0.08f, pos, thick * 0.92f, seamPaint)
        } else {
            canvas.drawLine(thick * 0.08f, pos, thick * 0.92f, pos, seamPaint)
        }
    }
}

/**
 * Recessed brushed-silver speaker panel across the top of the NC1020 lower deck:
 * 16-column matrix of dark speaker perforations on the left and spaced "www.ggv.com.cn"
 * silkscreen at the bottom-right.
 */
class WqxSpeakerGrilleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val panelTop = ContextCompat.getColor(context, R.color.wqx_speaker_panel_top)
    private val panelBot = ContextCompat.getColor(context, R.color.wqx_speaker_panel_bot)
    private val panelStroke = ContextCompat.getColor(context, R.color.wqx_speaker_panel_stroke)
    private val holeColor = ContextCompat.getColor(context, R.color.wqx_speaker_hole)
    private val urlColor = ContextCompat.getColor(context, R.color.wqx_speaker_url)

    private val panelRect = RectF()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = panelStroke
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = holeColor
    }
    private val urlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = urlColor
        typeface = Typeface.SANS_SERIF
        textAlign = Paint.Align.RIGHT
        letterSpacing = 0.24f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val inset = 0.7f * density
        panelRect.set(inset, inset, w - inset, h - inset)
        panelPaint.shader = LinearGradient(
            0f, panelRect.top, 0f, panelRect.bottom,
            panelTop, panelBot, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerR = 5.5f * density
        canvas.drawRoundRect(panelRect, cornerR, cornerR, panelPaint)
        canvas.drawRoundRect(panelRect, cornerR, cornerR, strokePaint)

        // Speaker hole grid on left (cols = 16, adaptive rows 4..8 so holes stay circular & evenly pitched)
        val cols = 16
        val gridLeft = w * 0.068f
        val gridRight = w * 0.355f
        val pitchX = (gridRight - gridLeft) / (cols - 1).toFloat()
        val padY = (h * 0.16f).coerceAtLeast(5f * density)
        val availH = (h - padY * 2f).coerceAtLeast(pitchX * 2f)
        val rows = ((availH / pitchX).toInt() + 1).coerceIn(4, 8)
        val pitchY = if (rows > 1) availH / (rows - 1).toFloat() else 0f
        val holeRadius = (min(pitchX, if (pitchY > 0f) pitchY else pitchX) * 0.28f)
            .coerceIn(1.2f * density, 2.6f * density)
        val startY = (h - pitchY * (rows - 1)) * 0.5f

        for (r in 0 until rows) {
            val cy = startY + r * pitchY
            for (c in 0 until cols) {
                val cx = gridLeft + c * pitchX
                canvas.drawCircle(cx, cy, holeRadius, holePaint)
            }
        }

        urlPaint.textSize = (h * 0.145f).coerceIn(8f * density, 11f * density)
        val urlX = w - 12f * density
        val urlY = h - (6.5f * density).coerceAtMost(h * 0.18f)
        canvas.drawText("www.ggv.com.cn", urlX, urlY, urlPaint)
    }
}

/**
 * Compact speaker perforation matrix used on the left side of the brushed-silver
 * [Speaker + F1..F4 + ON/OFF] deck bar in the "Full LCD" skin.
 */
class WqxSpeakerDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.wqx_speaker_hole)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.wqx_speaker_panel_stroke)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padX = 4f * density
        val rightInset = 7f * density
        val padY = (h * 0.18f).coerceAtLeast(5f * density)
        val availW = (w - padX - rightInset).coerceAtLeast(1f)
        val availH = (h - padY * 2f).coerceAtLeast(1f)
        val targetPitch = 6.2f * density

        val cols = ((availW / targetPitch).toInt() + 1).coerceIn(5, 11)
        val rows = ((availH / targetPitch).toInt() + 1).coerceIn(4, 6)
        val pitchX = if (cols > 1) availW / (cols - 1).toFloat() else 0f
        val pitchY = if (rows > 1) availH / (rows - 1).toFloat() else 0f
        val radius = (min(if (pitchX > 0f) pitchX else targetPitch, if (pitchY > 0f) pitchY else targetPitch) * 0.28f)
            .coerceIn(1.2f * density, 2.2f * density)

        val startX = padX + (availW - pitchX * (cols - 1)) * 0.5f
        val startY = (h - pitchY * (rows - 1)) * 0.5f

        for (r in 0 until rows) {
            val cy = startY + r * pitchY
            for (c in 0 until cols) {
                val cx = startX + c * pitchX
                canvas.drawCircle(cx, cy, radius, holePaint)
            }
        }

        val divX = w - 2f * density
        canvas.drawLine(divX, h * 0.16f, divX, h * 0.84f, dividerPaint)
    }
}
