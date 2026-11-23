package org.liberty.android.nc1020emu

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class KeypadLayout : LinearLayout {
    private val functionButtonsKeycode = intArrayOf(0x10, 0x11, 0x12, 0x13, 0x0f)
    private val functionLabels = arrayOf("F1 插入", "F2 删除", "F3 查找", "F4 修改")
    private val mainButtonsKeycode = arrayOf(
        intArrayOf(0x15, 0x14, KEYCODE_RESET, 0x0B, 0x0C, 0x0D, 0x0A, 0x09, 0x08, 0x0E),
        intArrayOf(0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x18, 0x1C),
        intArrayOf(0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x19, 0x1D),
        intArrayOf(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x1A, 0x1E),
        intArrayOf(0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x1B, 0x1F)
    )
    private var onButtonPressedListener: OnButtonPressedListener? = null
    private lateinit var mainKeypadLayout: LinearLayout
    private var defaultMainKeypadHeight = 0
    private var functionAreaHeight = 0
    private var keypadWellVerticalPadding = 0
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

        val functionHeight = resources.getDimensionPixelSize(R.dimen.function_keypad_height)
        val functionBottomMargin = resources.getDimensionPixelSize(R.dimen.function_keypad_bottom_margin)
        val functionButtonWidth = resources.getDimensionPixelSize(R.dimen.function_keypad_width)
        val pillWidth = resources.getDimensionPixelSize(R.dimen.function_pill_width)
        val pillHeight = resources.getDimensionPixelSize(R.dimen.function_pill_height)
        val onOffSize = resources.getDimensionPixelSize(R.dimen.onoff_button_size)
        val labelColor = ContextCompat.getColor(context, R.color.wqx_fn_label_text)
        val accentColor = ContextCompat.getColor(context, R.color.wqx_fn_accent_text)

        val functionKeypadLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.wqx_function_strip_bg)
            setPadding(dp(10f), dp(2f), dp(6f), dp(2f))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, functionHeight).apply {
                bottomMargin = functionBottomMargin
            }
        }

        val leftBadge = TextView(context).apply {
            text = "WQX"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            paint.isFakeBoldText = true
            letterSpacing = 0.14f
            isSingleLine = true
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        functionKeypadLayout.addView(leftBadge)

        for (idx in 0..3) {
            val fnCell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.keypad_button)
                layoutParams = LayoutParams(functionButtonWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val labelView = TextView(context).apply {
                text = functionLabels[idx]
                setTextColor(labelColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                paint.isFakeBoldText = true
                isSingleLine = true
                isDuplicateParentStateEnabled = true
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(2f)
                }
            }
            val pillCap = View(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.wqx_fn_pill_button)
                isDuplicateParentStateEnabled = true
                layoutParams = LayoutParams(pillWidth, pillHeight)
            }
            fnCell.addView(labelView)
            fnCell.addView(pillCap)
            attachButtonTouchHandler(fnCell, functionButtonsKeycode[idx])
            functionKeypadLayout.addView(fnCell)
        }

        val onOffCell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.keypad_button)
            layoutParams = LayoutParams(functionButtonWidth, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val onOffCap = View(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.wqx_onoff_button)
            isDuplicateParentStateEnabled = true
            layoutParams = LayoutParams(onOffSize, onOffSize)
        }
        val onOffLabel = TextView(context).apply {
            text = "ON/OFF"
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            paint.isFakeBoldText = true
            letterSpacing = 0.04f
            isSingleLine = true
            isDuplicateParentStateEnabled = true
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(1f)
            }
        }
        onOffCell.addView(onOffCap)
        onOffCell.addView(onOffLabel)
        attachButtonTouchHandler(onOffCell, functionButtonsKeycode[4])
        functionKeypadLayout.addView(onOffCell)

        addView(functionKeypadLayout)

        defaultMainKeypadHeight = resources.getDimensionPixelSize(R.dimen.main_keypad_height)
        functionAreaHeight = functionHeight + functionBottomMargin
        val wellPad = dp(4f)
        keypadWellVerticalPadding = wellPad * 2

        val keypadWell = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.wqx_keypad_well_bg)
            setPadding(wellPad, wellPad, wellPad, wellPad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        mainKeypadLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                defaultMainKeypadHeight
            )
            background = ContextCompat.getDrawable(context, R.drawable.keypad)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = ContextCompat.getDrawable(context, R.drawable.wqx_keypad_veil)
            }
        }

        val buttonParam = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        for (j in 0..4) {
            val rowLayout = LinearLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (i in 0..9) {
                val button = Button(context).apply {
                    layoutParams = buttonParam
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setBackgroundResource(R.drawable.keypad_button)
                }
                attachButtonTouchHandler(button, mainButtonsKeycode[j][i])
                rowLayout.addView(button)
            }
            mainKeypadLayout.addView(rowLayout)
        }

        keypadWell.addView(mainKeypadLayout)
        addView(keypadWell)
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
        val targetMainHeight = if (heightMode != MeasureSpec.UNSPECIFIED) {
            val availableForMain = (
                heightSize - paddingTop - paddingBottom - functionAreaHeight - keypadWellVerticalPadding
            ).coerceAtLeast(1)
            minOf(defaultMainKeypadHeight, availableForMain)
        } else {
            defaultMainKeypadHeight
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
        const val HOLD_THRESHOLD_MS = 500L
    }
}
