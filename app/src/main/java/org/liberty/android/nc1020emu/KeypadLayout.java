package org.liberty.android.nc1020emu;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class KeypadLayout extends LinearLayout {
    private int[] functionButtonsKeycode = {0x10, 0x11, 0x12, 0x13, 0x0f};
    private int[][] mainButtonsKeycode = {
            {-1, -1, -1, 0x0B, 0x0C, 0x0D, 0x0A, 0x09, 0x08, 0x0E},
            {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x18, 0x1C},
            {0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x19, 0x1D},
            {0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x1A, 0x1E},
            {0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x1B, 0x1F},
    };

    private OnButtonPressedListener onButtonPressedListener;

    private OnTouchListener onButtonTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int keyCode = (Integer) v.getTag();

            if (keyCode == -1 || onButtonPressedListener == null) {
                return false;
            }

            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                onButtonPressedListener.onKeyDown(keyCode);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                onButtonPressedListener.onKeyUp(keyCode);
            }
            return false;
        }
    };

    public KeypadLayout(Context context) {
        super(context);
        init();
    }

    public KeypadLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KeypadLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void init() {
        setOrientation(VERTICAL);
        LinearLayout functionKeypadLayout = new LinearLayout(getContext());
        LayoutParams functionKeypadParam = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        functionKeypadParam.bottomMargin = getResources().getDimensionPixelSize(R.dimen.function_keypad_bottom_margin);
        functionKeypadLayout.setLayoutParams(functionKeypadParam);

        functionKeypadLayout.setOrientation(HORIZONTAL);
        functionKeypadLayout.setHorizontalGravity(Gravity.END);
        LayoutParams functionButtonParams = new LayoutParams(getResources().getDimensionPixelSize(R.dimen.function_keypad_width), getResources().getDimensionPixelSize(R.dimen.function_keypad_height));

        ImageButton f1Button = new ImageButton(getContext());
        f1Button.setImageResource(R.drawable.f1);
        int padding = getResources().getDimensionPixelSize(R.dimen.function_key_padding);
        f1Button.setPadding(padding, padding, padding, padding);
        f1Button.setLayoutParams(functionButtonParams);
        f1Button.setScaleType(ImageView.ScaleType.FIT_XY);
        f1Button.setOnTouchListener(onButtonTouchListener);
        f1Button.setTag(functionButtonsKeycode[0]);
        functionKeypadLayout.addView(f1Button);

        ImageButton f2Button = new ImageButton(getContext());
        f2Button.setImageResource(R.drawable.f2);
        f2Button.setLayoutParams(functionButtonParams);
        f2Button.setScaleType(ImageView.ScaleType.FIT_XY);
        f2Button.setOnTouchListener(onButtonTouchListener);
        f2Button.setTag(functionButtonsKeycode[1]);
        functionKeypadLayout.addView(f2Button);

        ImageButton f3Button = new ImageButton(getContext());
        f3Button.setImageResource(R.drawable.f3);
        f3Button.setLayoutParams(functionButtonParams);
        f3Button.setScaleType(ImageView.ScaleType.FIT_XY);
        f3Button.setOnTouchListener(onButtonTouchListener);
        f3Button.setTag(functionButtonsKeycode[2]);
        functionKeypadLayout.addView(f3Button);

        ImageButton f4Button = new ImageButton(getContext());
        f4Button.setImageResource(R.drawable.f4);
        f4Button.setLayoutParams(functionButtonParams);
        f4Button.setScaleType(ImageView.ScaleType.FIT_XY);
        f4Button.setOnTouchListener(onButtonTouchListener);
        f4Button.setTag(functionButtonsKeycode[3]);
        functionKeypadLayout.addView(f4Button);

        ImageButton onoffButton = new ImageButton(getContext());
        onoffButton.setImageResource(R.drawable.onoff);
        onoffButton.setLayoutParams(functionButtonParams);
        onoffButton.setScaleType(ImageView.ScaleType.FIT_XY);
        onoffButton.setOnTouchListener(onButtonTouchListener);
        onoffButton.setTag(functionButtonsKeycode[4]);
        functionKeypadLayout.addView(onoffButton);


        addView(functionKeypadLayout);

        LinearLayout mainKeypadLayout = new LinearLayout(getContext());
        mainKeypadLayout.setOrientation(VERTICAL);
        mainKeypadLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.main_keypad_height)));
        mainKeypadLayout.setBackground(getContext().getDrawable(R.drawable.keypad));

        LayoutParams rowParam = new LayoutParams(0, LayoutParams.MATCH_PARENT);
        rowParam.weight = 1;
        rowParam.leftMargin = 0;
        rowParam.rightMargin = 0;
        for (int j = 0; j < 5; j++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            for (int i = 0; i < 10; i++) {
                Button button = new Button(getContext());
                button.setLayoutParams(rowParam);
                button.setBackgroundResource(R.drawable.keypad_button);
                button.setTag(mainButtonsKeycode[j][i]);
                button.setOnTouchListener(onButtonTouchListener);
                rowLayout.addView(button);
            }
            mainKeypadLayout.addView(rowLayout);
        }
        addView(mainKeypadLayout);

    }

    public void setOnButtonTouchListener(OnButtonPressedListener onButtonPressedListener) {
        this.onButtonPressedListener = onButtonPressedListener;
    }

    public interface OnButtonPressedListener {
        void onKeyDown(int keyCode);

        void onKeyUp(int keyCode);
    }
}
