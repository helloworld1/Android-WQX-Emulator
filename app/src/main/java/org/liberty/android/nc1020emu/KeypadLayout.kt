package org.liberty.android.nc1020emu

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import org.liberty.android.nc1020emu.KeypadLayout.OnButtonPressedListener
import android.view.View.OnTouchListener
import android.view.MotionEvent
import android.view.ViewGroup
import org.liberty.android.nc1020emu.R
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView

class KeypadLayout : LinearLayout {
    private val functionButtonsKeycode = intArrayOf(0x10, 0x11, 0x12, 0x13, 0x0f)
    private val mainButtonsKeycode = arrayOf(intArrayOf(-1, -1, -1, 0x0B, 0x0C, 0x0D, 0x0A, 0x09, 0x08, 0x0E), intArrayOf(0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x18, 0x1C), intArrayOf(0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x19, 0x1D), intArrayOf(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x1A, 0x1E), intArrayOf(0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x1B, 0x1F))
    private var onButtonPressedListener: OnButtonPressedListener? = null
    private val onButtonTouchListener = OnTouchListener { v, event ->
        val keyCode = v.tag as Int
        if (keyCode == -1 || onButtonPressedListener == null) {
            return@OnTouchListener false
        }
        val action = event.action
        if (action == MotionEvent.ACTION_DOWN) {
            onButtonPressedListener!!.onKeyDown(keyCode)
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            onButtonPressedListener!!.onKeyUp(keyCode)
        }
        false
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

    fun init() {
        orientation = VERTICAL
        val functionKeypadLayout = LinearLayout(context)
        val functionKeypadParam = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        functionKeypadParam.bottomMargin = resources.getDimensionPixelSize(R.dimen.function_keypad_bottom_margin)
        functionKeypadLayout.layoutParams = functionKeypadParam
        functionKeypadLayout.orientation = HORIZONTAL
        functionKeypadLayout.setHorizontalGravity(Gravity.END)
        val functionButtonParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.function_keypad_width), resources.getDimensionPixelSize(R.dimen.function_keypad_height))
        val f1Button = ImageButton(context)
        f1Button.setImageResource(R.drawable.f1)
        val padding = resources.getDimensionPixelSize(R.dimen.function_key_padding)
        f1Button.layoutParams = functionButtonParams
        f1Button.scaleType = ImageView.ScaleType.FIT_XY
        f1Button.setOnTouchListener(onButtonTouchListener)
        f1Button.tag = functionButtonsKeycode[0]
        functionKeypadLayout.addView(f1Button)
        val f2Button = ImageButton(context)
        f2Button.setImageResource(R.drawable.f2)
        f2Button.layoutParams = functionButtonParams
        f2Button.scaleType = ImageView.ScaleType.FIT_XY
        f2Button.setOnTouchListener(onButtonTouchListener)
        f2Button.tag = functionButtonsKeycode[1]
        functionKeypadLayout.addView(f2Button)
        val f3Button = ImageButton(context)
        f3Button.setImageResource(R.drawable.f3)
        f3Button.layoutParams = functionButtonParams
        f3Button.scaleType = ImageView.ScaleType.FIT_XY
        f3Button.setOnTouchListener(onButtonTouchListener)
        f3Button.tag = functionButtonsKeycode[2]
        functionKeypadLayout.addView(f3Button)
        val f4Button = ImageButton(context)
        f4Button.setImageResource(R.drawable.f4)
        f4Button.layoutParams = functionButtonParams
        f4Button.scaleType = ImageView.ScaleType.FIT_XY
        f4Button.setOnTouchListener(onButtonTouchListener)
        f4Button.tag = functionButtonsKeycode[3]
        functionKeypadLayout.addView(f4Button)
        val onoffButton = ImageButton(context)
        onoffButton.setImageResource(R.drawable.onoff)
        onoffButton.layoutParams = functionButtonParams
        onoffButton.scaleType = ImageView.ScaleType.FIT_XY
        onoffButton.setOnTouchListener(onButtonTouchListener)
        onoffButton.tag = functionButtonsKeycode[4]
        functionKeypadLayout.addView(onoffButton)
        addView(functionKeypadLayout)
        val mainKeypadLayout = LinearLayout(context)
        mainKeypadLayout.orientation = VERTICAL
        mainKeypadLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.main_keypad_height))
        mainKeypadLayout.background = context.getDrawable(R.drawable.keypad)
        val rowParam = LayoutParams(0, LayoutParams.MATCH_PARENT)
        rowParam.weight = 1f
        rowParam.leftMargin = 0
        rowParam.rightMargin = 0
        for (j in 0..4) {
            val rowLayout = LinearLayout(context)
            for (i in 0..9) {
                val button = Button(context)
                button.layoutParams = rowParam
                button.setBackgroundResource(R.drawable.keypad_button)
                button.tag = mainButtonsKeycode[j][i]
                button.setOnTouchListener(onButtonTouchListener)
                rowLayout.addView(button)
            }
            mainKeypadLayout.addView(rowLayout)
        }
        addView(mainKeypadLayout)
    }

    fun setOnButtonTouchListener(onButtonPressedListener: OnButtonPressedListener?) {
        this.onButtonPressedListener = onButtonPressedListener
    }

    interface OnButtonPressedListener {
        fun onKeyDown(keyCode: Int)
        fun onKeyUp(keyCode: Int)
    }
}