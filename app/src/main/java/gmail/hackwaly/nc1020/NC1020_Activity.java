package gmail.hackwaly.nc1020;

import gmail.hackwaly.nc1020.NC1020_KeypadView.OnKeyListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class NC1020_Activity extends Activity implements Callback, OnKeyListener {
    private static final int FRAME_RATE = 50;
    private static final int FRAME_INTERVAL = 1000 / FRAME_RATE;

    private byte[] lcdBuffer;
    private byte[] lcdBufferEx;
    private Bitmap lcdBitmap;
    private Matrix lcdMatrix;
    private SurfaceHolder lcdSurfaceHolder;
    private SharedPreferences prefs;
    private boolean speedUp;
    private Handler handler = new Handler();

    float displayScale = 1f;

    private final Runnable frameRunnable = new Runnable(){

        @Override
        public void run() {
            NC1020_JNI.RunTimeSlice(FRAME_INTERVAL, speedUp);
            handler.postDelayed(frameRunnable, FRAME_INTERVAL);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    updateLcd();

                }
            });
        }

    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        NC1020_KeypadView gmudKeypad = (NC1020_KeypadView) findViewById(R.id.gmud_keypad);
        gmudKeypad.setOnKeyListener(this);

        SurfaceView lcdSurfaceView = (SurfaceView) findViewById(R.id.lcd);
        int width = (int) (getScreenWidth() * 0.9f);
        displayScale = (float) width / 160;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams( width, width / 2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        lcdSurfaceView.setLayoutParams(params);

        lcdSurfaceHolder = lcdSurfaceView.getHolder();
        lcdBuffer = new byte[1600];
        lcdBufferEx = new byte[1600 * 8];
        lcdBitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ALPHA_8);
        lcdMatrix = new Matrix();

        lcdSurfaceHolder.addCallback(this);
        String dir = initDataFolder();
        NC1020_JNI.Initialize(dir);
        NC1020_JNI.Load();

    }

    @Override
    public void onResume() {
        handler.post(frameRunnable);

        super.onResume();
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(frameRunnable);
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            NC1020_JNI.SetKey(0x3B, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)  {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            NC1020_JNI.SetKey(0x3B, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.action_speed_up).setChecked(
                prefs.getBoolean("SpeedUp",	false));
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
                NC1020_JNI.Reset();
                return true;
            case R.id.action_speed_up:
                if (item.isChecked()) {
                    item.setChecked(false);
                } else {
                    item.setChecked(true);
                }
                speedUp = item.isChecked();
                prefs.edit().putBoolean("SpeedUp", item.isChecked()).commit();
                return true;
            case R.id.action_load:
                NC1020_JNI.Load();
                return true;
            case R.id.action_save:
                NC1020_JNI.Save();
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

    @Override
    public void onKeyDown(int keyId) {
        NC1020_JNI.SetKey(keyId, true);
    }

    @Override
    public void onKeyUp(int keyId) {
        NC1020_JNI.SetKey(keyId, false);
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
        if (!NC1020_JNI.CopyLcdBuffer(lcdBuffer)) {
            return;
        }
        Canvas lcdCanvas = lcdSurfaceHolder.lockCanvas();
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
        lcdBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(lcdBufferEx));
        lcdCanvas.drawColor(0xFF72B056);
        lcdCanvas.drawBitmap(lcdBitmap, lcdMatrix, null);

        lcdSurfaceHolder.unlockCanvasAndPost(lcdCanvas);
    }

}
