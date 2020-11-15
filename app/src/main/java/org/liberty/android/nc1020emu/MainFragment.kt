package org.liberty.android.nc1020emu

import org.liberty.android.nc1020emu.NC1020JNI.runTimeSlice
import org.liberty.android.nc1020emu.NC1020JNI.copyLcdBufferEx
import org.liberty.android.nc1020emu.NC1020JNI.save
import org.liberty.android.nc1020emu.NC1020JNI.setKey
import org.liberty.android.nc1020emu.NC1020JNI.reset
import org.liberty.android.nc1020emu.NC1020JNI.load
import org.liberty.android.nc1020emu.NC1020JNI.initialize
import org.liberty.android.nc1020emu.NC1020JNI.cycles
import android.view.SurfaceHolder
import android.view.Choreographer.FrameCallback
import android.graphics.Bitmap
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.ViewGroup
import android.os.Bundle
import org.liberty.android.nc1020emu.KeypadLayout.OnButtonPressedListener
import android.widget.LinearLayout
import android.view.Gravity
import androidx.navigation.Navigation
import android.view.Choreographer
import android.content.DialogInterface
import android.graphics.Matrix
import android.graphics.Point
import android.util.Log
import kotlin.Throws
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kotlinx.android.synthetic.main.main_fragment.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max

class MainFragment : Fragment(), SurfaceHolder.Callback, FrameCallback {
    private val lcdBufferEx = ByteArray(1600 * 8)
    private var lcdBitmap= Bitmap.createBitmap(160, 80, Bitmap.Config.ALPHA_8)
    private var lcdMatrix = Matrix()
    private var speedUp = false
    private val executorService = Executors.newSingleThreadExecutor()
    private var isRunning = true
    private var displayScale = 1f
    private var lastFrameTime: Long = 0
    private var lastCycles: Long = 0
    private var frames: Long = 0
    private lateinit var preferences: SharedPreferences

    private val runnable = Runnable {
        var interval = 0L
        while (isRunning) {
            val startTime = System.currentTimeMillis()
            runTimeSlice(interval.toInt(), speedUp)
            copyLcdBufferEx(lcdBufferEx)
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < FRAME_INTERVAL) {
                try {
                    // If speedUp, don't sleep, run as much frames as system can
                    Thread.sleep(if (speedUp) 0 else FRAME_INTERVAL - elapsed)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            interval = max(elapsed, FRAME_INTERVAL.toLong())
        }
        if (saveStatesSetting) {
            save()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return LayoutInflater.from(context).inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        keypad_layout.setOnButtonTouchListener(object : OnButtonPressedListener {
            override fun onKeyDown(keyCode: Int) {
                setKey(keyCode, true)
            }

            override fun onKeyUp(keyCode: Int) {
                setKey(keyCode, false)
            }
        })
        val width = screenWidth
        displayScale = width.toFloat() / 160
        val params = LinearLayout.LayoutParams(width, width / 2)
        params.gravity = Gravity.CENTER_HORIZONTAL
        lcd_surface.layoutParams = params
        lcd_surface.holder.addCallback(this)
        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        setupToolbar(toolbar)
        initEmulation()
    }

    private fun setupToolbar(toolbar: Toolbar) {
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_quit -> {
                    requireActivity().finish()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_restart -> {
                    reset()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_speed_up -> {
                    item.isChecked = !item.isChecked
                    speedUp = item.isChecked
                    return@setOnMenuItemClickListener true
                }
                R.id.action_load -> {
                    load()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_save -> {
                    save()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_factory_reset -> {
                    showFactoryResetDialog()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_settings -> {
                    Navigation.findNavController(toolbar).navigate(R.id.action_mainFragment_to_settingsFragment)
                    return@setOnMenuItemClickListener true
                }
                else -> return@setOnMenuItemClickListener false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startEmulation()
    }

    override fun onPause() {
        super.onPause()
        stopEmulation()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int,
                                height: Int) {
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lcdMatrix.setScale(displayScale, displayScale)
        val lcdCanvas = lcd_surface.holder.lockCanvas()
        lcdCanvas.drawColor(ContextCompat.getColor(requireContext(), R.color.lcd_background))
        lcd_surface.holder.unlockCanvasAndPost(lcdCanvas)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}
    private fun initEmulation() {
        initDataFolder()
        val fileDir = requireContext().applicationContext.filesDir.absolutePath
        val romPath = "$fileDir/$ROM_FILE_NAME"
        val norPath = "$fileDir/$NOR_FILE_NAME"
        val statePath = "$fileDir/$STATE_FILE_NAME"
        initialize(romPath, norPath, statePath)
        load()
    }

    private fun startEmulation() {
        Choreographer.getInstance().postFrameCallback(this)
        isRunning = true
        executorService.submit(runnable)
    }

    private fun stopEmulation() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private val saveStatesSetting: Boolean
        get() = preferences.getBoolean(SAVE_STATES_KEY, true)

    private fun showFactoryResetDialog() {
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.factory_reset)
                .setMessage(R.string.factory_reset_message)
                .setPositiveButton(R.string.yes) { _: DialogInterface?, _: Int -> factoryReset() }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun initDataFolder() {
        val filesDir = requireContext().applicationContext.filesDir
        try {
            copyFileFromAsset(ROM_FILE_NAME, filesDir)
            copyFileFromAsset(NOR_FILE_NAME, filesDir)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    private fun copyFileFromAsset(fileName: String, folder: File) {
        val dest = File(folder.absoluteFile.toString() + "/" + fileName)
        if (dest.exists()) {
            return
        }
        resources.assets.open(fileName).use { `in` ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(8192)
                var count: Int
                while (`in`.read(buffer).also { count = it } != -1) {
                    out.write(buffer, 0, count)
                }
            }
        }
    }

    private fun factoryReset() {
        val filesDir = requireContext().applicationContext.filesDir.absolutePath
        val norFile = File("$filesDir/$NOR_FILE_NAME")
        val stateFile = File("$filesDir/$STATE_FILE_NAME")
        if (!norFile.delete()) {
            Log.e(TAG, "Error deleting nor file")
        }
        if (!stateFile.delete()) {
            Log.e(TAG, "Error deleting state file")
        }
        initEmulation()
        reset()
    }

    private val screenWidth: Int
        get() {
            val display = requireActivity().windowManager.defaultDisplay
            val size = Point()
            display.getSize(size)
            return size.x
        }

    private fun updateLcd() {
        val lcdCanvas = lcd_surface.holder.lockCanvas() ?: return
        synchronized(lcdBufferEx) { lcdBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(lcdBufferEx)) }
        lcdCanvas.drawColor(ContextCompat.getColor(requireContext(), R.color.lcd_background))
        lcdCanvas.drawBitmap(lcdBitmap, lcdMatrix, null)
        lcd_surface.holder.unlockCanvasAndPost(lcdCanvas)
    }

    private fun displayPerf() {
        frames++
        val now = System.currentTimeMillis()
        val elapse = now - lastFrameTime
        if (elapse > 1000L) {
            val fps = frames * 1000 / elapse
            val percentage = (cycles - lastCycles) * 100 / CYCLES_SECOND
            info_text.text = String.format(getString(R.string.perf_text), fps, cycles, percentage)
            lastCycles = cycles
            lastFrameTime = now
            frames = 0
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        displayPerf()
        updateLcd()
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        private val TAG = MainFragment::class.java.simpleName
        private const val FRAME_RATE = 60
        private const val FRAME_INTERVAL = 1000 / FRAME_RATE
        private const val CYCLES_SECOND: Long = 5120000
        private const val ROM_FILE_NAME = "obj_lu.bin"
        private const val NOR_FILE_NAME = "nc1020.fls"
        private const val STATE_FILE_NAME = "nc1020.sts"
        private const val SAVE_STATES_KEY = "save_states"
    }
}