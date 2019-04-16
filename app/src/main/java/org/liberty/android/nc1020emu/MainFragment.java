package org.liberty.android.nc1020emu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

public class MainFragment extends Fragment implements SurfaceHolder.Callback, Choreographer.FrameCallback{
    private static final String TAG = MainFragment.class.getSimpleName();
    private static final int FRAME_RATE = 60;
    private static final int FRAME_INTERVAL = 1000 / FRAME_RATE;
    private static final long CYCLES_SECOND = 5120000;

    private static final String ROM_FILE_NAME = "obj_lu.bin";
    private static final String NOR_FILE_NAME = "nc1020.fls";
    private static final String STATE_FILE_NAME = "nc1020.sts";

    private static final String SAVE_STATES_KEY = "save_states";

    private final byte[] lcdBuffer = new byte[1600];
    private final byte[] lcdBufferEx = new byte[1600 * 8];

    private Bitmap lcdBitmap;
    private Matrix lcdMatrix;
    private SurfaceHolder lcdSurfaceHolder;
    private boolean speedUp;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isRunning = true;

    private float displayScale = 1f;
    private long lastFrameTime;
    private long lastCycles;
    private long frames;
    private TextView infoText;
    private SharedPreferences preferences;

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            long interval = 0L;
            while (isRunning) {
                long startTime = System.currentTimeMillis();
                NC1020JNI.runTimeSlice((int)interval, speedUp);

                if (NC1020JNI.copyLcdBuffer(lcdBuffer)) {
                    synchronized (lcdBufferEx) {
                        for (int y = 0; y < 80; y++) {
                            for (int j = 0; j < 20; j++) {
                                byte p = lcdBuffer[20 * y + j];
                                for (int k = 0; k < 8; k++) {
                                    lcdBufferEx[y * 160 + j * 8 + k] = (byte) ((p & (1 << (7 - k))) != 0 ? 0xFF
                                            : 0x00);
                                }
                            }
                        }
                        for (int y = 0; y < 80; y++) {
                            lcdBufferEx[y * 160] = 0;
                        }
                    }
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed < FRAME_INTERVAL) {
                    try {
                        // If speedUp, don't sleep, run as much frames as system can
                        Thread.sleep(speedUp ? 0 : FRAME_INTERVAL - elapsed);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                interval = Math.max(elapsed, FRAME_INTERVAL);
            }

            if (getSaveStatesSetting()) {
                NC1020JNI.save();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return LayoutInflater.from(getContext()).inflate(R.layout.main_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

        KeypadLayout keypad = view.findViewById(R.id.keypad_layout);
        keypad.setOnButtonTouchListener(new KeypadLayout.OnButtonPressedListener() {
            @Override
            public void onKeyDown(int keyCode) {
                NC1020JNI.setKey(keyCode, true);
            }

            @Override
            public void onKeyUp(int keyCode) {
                NC1020JNI.setKey(keyCode, false);
            }
        });

        SurfaceView lcdSurfaceView = view.findViewById(R.id.lcd);
        int width = getScreenWidth();
        displayScale = (float) width / 160;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams( width, width / 2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        lcdSurfaceView.setLayoutParams(params);

        lcdSurfaceHolder = lcdSurfaceView.getHolder();
        lcdBitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ALPHA_8);
        lcdMatrix = new Matrix();

        infoText = view.findViewById(R.id.info);
        lcdSurfaceHolder.addCallback(this);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        setupToolbar(toolbar);


        initEmulation();
    }

    private void setupToolbar(Toolbar toolbar) {
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_quit:
                    getActivity().finish();
                    return true;
                case R.id.action_restart:
                    NC1020JNI.reset();
                    return true;
                case R.id.action_speed_up:
                    if (item.isChecked()) {
                        item.setChecked(false);
                    } else {
                        item.setChecked(true);
                    }
                    speedUp = item.isChecked();
                    return true;
                case R.id.action_load:
                    NC1020JNI.load();
                    return true;
                case R.id.action_save:
                    NC1020JNI.save();
                    return true;
                case R.id.action_factory_reset:
                    showFactoryResetDialog();
                    return true;

                case R.id.action_settings:
                    Navigation.findNavController(toolbar).navigate(R.id.action_mainFragment_to_settingsFragment);
                    return true;

                default:
                    return false;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        startEmulation();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopEmulation();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        lcdMatrix.setScale(displayScale, displayScale);

        Canvas lcdCanvas = lcdSurfaceHolder.lockCanvas();
        lcdCanvas.drawColor(getResources().getColor(R.color.lcd_background));
        lcdSurfaceHolder.unlockCanvasAndPost(lcdCanvas);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void initEmulation() {
        initDataFolder();

        String fileDir = getContext().getApplicationContext().getFilesDir().getAbsolutePath();
        String romPath = fileDir + "/" + ROM_FILE_NAME;
        String norPath = fileDir + "/" + NOR_FILE_NAME;
        String statePath = fileDir + "/" + STATE_FILE_NAME;

        NC1020JNI.initialize(romPath, norPath, statePath);
        NC1020JNI.load();
    }

    private void startEmulation() {
        Choreographer.getInstance().postFrameCallback(this);
        isRunning = true;
        executorService.submit(runnable);
    }

    private void stopEmulation() {
        isRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    private boolean getSaveStatesSetting() {
        return preferences.getBoolean(SAVE_STATES_KEY, true);
    }

    private void showFactoryResetDialog() {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.factory_reset)
                .setMessage(R.string.factory_reset_message)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    factoryReset();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void initDataFolder() {
        File filesDir = getContext().getApplicationContext().getFilesDir();
        try {
            copyFileFromAsset(ROM_FILE_NAME, filesDir);
            copyFileFromAsset(NOR_FILE_NAME, filesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void copyFileFromAsset(String fileName, File folder) throws IOException {
        File dest = new File(folder.getAbsoluteFile() + "/" + fileName);
        if (dest.exists()) {
            return;
        }
        try (InputStream in = getResources().getAssets().open(fileName)) {

            try (FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];

                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
        }
    }

    private void factoryReset() {
        String filesDir = getContext().getApplicationContext().getFilesDir().getAbsolutePath();
        File norFile = new File(filesDir + "/" + NOR_FILE_NAME);
        File stateFile = new File(filesDir + "/" + STATE_FILE_NAME);


        if (!norFile.delete()) {
            Log.e(TAG, "Error deleting nor file");
        }

        if (!stateFile.delete()) {
            Log.e(TAG, "Error deleting state file");
        }

        initEmulation();
        NC1020JNI.reset();
    }

    private int getScreenWidth() {
        Display display = getActivity().getWindowManager(). getDefaultDisplay();
        Point size = new Point();
        display. getSize(size);
        return size.x;
    }

    private void updateLcd() {
        Canvas lcdCanvas = lcdSurfaceHolder.lockCanvas();
        if (lcdCanvas == null) {
            return;
        }

        synchronized (lcdBufferEx) {
            lcdBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(lcdBufferEx));
        }
        lcdCanvas.drawColor(getResources().getColor(R.color.lcd_background));
        lcdCanvas.drawBitmap(lcdBitmap, lcdMatrix, null);

        lcdSurfaceHolder.unlockCanvasAndPost(lcdCanvas);
    }

    private void displayPerf() {
        frames++;
        long now = System.currentTimeMillis();
        long elapse = now - lastFrameTime;
        if (elapse > 1000L) {
            long fps = frames * 1000 / elapse;
            long cycles = NC1020JNI.getCycles();
            long percentage = (cycles - lastCycles) * 100 / CYCLES_SECOND;
            infoText.setText(String.format(getString(R.string.perf_text), fps, cycles, percentage));
            lastCycles = cycles;
            lastFrameTime = now;
            frames = 0;
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        displayPerf();
        updateLcd();
        Choreographer.getInstance().postFrameCallback(this);
    }
}
