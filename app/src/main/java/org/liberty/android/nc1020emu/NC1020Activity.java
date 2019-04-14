package org.liberty.android.nc1020emu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Point;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class NC1020Activity extends AppCompatActivity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final int FRAME_RATE = 60;
    private static final int FRAME_INTERVAL = 1000 / FRAME_RATE;
    private static final long CYCLES_SECOND = 5120000;

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

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            long interval = 0L;
            while (isRunning) {
                long startTime = System.currentTimeMillis();
                NC1020JNI.RunTimeSlice((int)interval, speedUp);

                if (NC1020JNI.CopyLcdBuffer(lcdBuffer)) {
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
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        KeypadLayout keypad = findViewById(R.id.keypad_layout);
        keypad.setOnButtonTouchListener(new KeypadLayout.OnButtonPressedListener() {
            @Override
            public void onKeyDown(int keyCode) {
                NC1020JNI.SetKey(keyCode, true);
            }

            @Override
            public void onKeyUp(int keyCode) {
                NC1020JNI.SetKey(keyCode, false);
            }
        });

        SurfaceView lcdSurfaceView = findViewById(R.id.lcd);
        int width = getScreenWidth();
        displayScale = (float) width / 160;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams( width, width / 2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        lcdSurfaceView.setLayoutParams(params);

        lcdSurfaceHolder = lcdSurfaceView.getHolder();
        lcdBitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ALPHA_8);
        lcdMatrix = new Matrix();

        infoText = findViewById(R.id.info);

        lcdSurfaceHolder.addCallback(this);

        String dir = initDataFolder();
        NC1020JNI.Initialize(dir);
        NC1020JNI.Load();
    }

    @Override
    public void onResume() {
        super.onResume();
        Choreographer.getInstance().postFrameCallback(this);
        isRunning = true;
        executorService.submit(runnable);
    }

    @Override
    public void onPause() {
        isRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            NC1020JNI.SetKey(0x3B, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            NC1020JNI.SetKey(0x3B, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.action_speed_up).setChecked(false);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_quit:
                finish();
                return true;
            case R.id.action_restart:
                NC1020JNI.Reset();
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
                NC1020JNI.Load();
                return true;
            case R.id.action_save:
                NC1020JNI.Save();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        lcdMatrix.setScale(displayScale, displayScale);

        Canvas lcdCanvas = lcdSurfaceHolder.lockCanvas();
        lcdCanvas.drawColor(0xFF72B056);
        lcdSurfaceHolder.unlockCanvasAndPost(lcdCanvas);
    }



    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private String initDataFolder() {
        File filesDir = getApplicationContext().getFilesDir();
        try {
            copyFileFromAsset("nc1020.fls", filesDir);
            copyFileFromAsset("obj_lu.bin", filesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return filesDir.getAbsolutePath();
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

    private int getScreenWidth() {
        Display display = getWindowManager(). getDefaultDisplay();
        Point size = new Point();
        display. getSize(size);
        return size. x;
    }

    private void updateLcd() {
        Canvas lcdCanvas = lcdSurfaceHolder.lockCanvas();
        if (lcdCanvas == null) {
            return;
        }

        synchronized (lcdBufferEx) {
            lcdBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(lcdBufferEx));
        }
        lcdCanvas.drawColor(0xFF72B056);
        lcdCanvas.drawBitmap(lcdBitmap, lcdMatrix, null);

        lcdSurfaceHolder.unlockCanvasAndPost(lcdCanvas);
    }

    private void displayPerf() {
        frames++;
        long now = System.currentTimeMillis();
        long elapse = now - lastFrameTime;
        if (elapse > 1000L) {
            long fps = frames * 1000 / elapse;
            long cycles = NC1020JNI.GetCycles();
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
