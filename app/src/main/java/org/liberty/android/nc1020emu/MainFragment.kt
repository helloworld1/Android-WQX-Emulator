package org.liberty.android.nc1020emu

import org.liberty.android.nc1020emu.NC1020JNI.runTimeSlice
import org.liberty.android.nc1020emu.NC1020JNI.copyLcdBufferEx
import org.liberty.android.nc1020emu.NC1020JNI.startAudio
import org.liberty.android.nc1020emu.NC1020JNI.stopAudio
import org.liberty.android.nc1020emu.NC1020JNI.setAudioEnabled
import org.liberty.android.nc1020emu.NC1020JNI.syncTime
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
import android.content.res.Configuration
import android.graphics.Matrix
import android.util.Log
import kotlin.Throws
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import android.view.SurfaceView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MainFragment : Fragment(), SurfaceHolder.Callback, FrameCallback {
    private val lcdBufferEx = ByteArray(1600 * 8)
    private val wakeMonitor = Object()
    private val pendingKeyPresses = ArrayDeque<Int>()
    private var clearActiveKeyRequested = false
    private var lcdBitmap= Bitmap.createBitmap(160, 80, Bitmap.Config.ALPHA_8)
    private var lcdMatrix = Matrix()
    @Volatile
    private var speedUp = false
    private val executorService = Executors.newSingleThreadExecutor()
    private var runningFuture: Future<*>? = null
    @Volatile
    private var isRunning = true
    private var displayScale = 1f
    private var lastFrameTime: Long = 0
    private var lastCycles: Long = 0
    private var frames: Long = 0
    private lateinit var preferences: SharedPreferences
    private lateinit var emulatorContainer: LinearLayout
    private lateinit var lcdContainer: LinearLayout
    private lateinit var keypadLayout: KeypadLayout
    private lateinit var lcdSurface: SurfaceView
    private lateinit var infoText: TextView

    private val runnable = Runnable {
        syncSystemTime()
        val soundOn = beepEnabledSetting
        setAudioEnabled(soundOn && !speedUp)
        if (soundOn) {
            startAudio()
        }
        var lastSliceNs = System.nanoTime()
        var lastLcdCopyNs = 0L
        var pendingNs = 0L
        var debtNs = 0L
        var noRepayUntilNs = 0L
        var activeKeyCode = -1
        var keyDownAtCycles = 0L
        var releaseAtCycles = 0L
        var nextKeyReadyAtCycles = 0L
        try {
            while (isRunning) {
                val nowNs = System.nanoTime()
                pendingNs += (nowNs - lastSliceNs).coerceIn(0L, 100_000_000L)
                lastSliceNs = nowNs

                var justPressedKey = false
                var justReleasedKey = false

                var shouldClearKey = false
                var hasQueuedKey = false
                synchronized(wakeMonitor) {
                    if (clearActiveKeyRequested) {
                        clearActiveKeyRequested = false
                        shouldClearKey = true
                    }
                    hasQueuedKey = pendingKeyPresses.isNotEmpty()
                }

                if (shouldClearKey) {
                    if (activeKeyCode != -1) {
                        setKey(activeKeyCode, false)
                        activeKeyCode = -1
                    }
                    nextKeyReadyAtCycles = 0L
                }

                val currentCycles = cycles
                if (activeKeyCode != -1) {
                    val heldCycles = currentCycles - keyDownAtCycles
                    if (currentCycles >= releaseAtCycles ||
                        (hasQueuedKey && heldCycles >= MIN_KEY_HOLD_CYCLES)
                    ) {
                        setKey(activeKeyCode, false)
                        activeKeyCode = -1
                        nextKeyReadyAtCycles = currentCycles + KEY_GAP_CYCLES
                        justReleasedKey = true
                    }
                }

                if (activeKeyCode == -1 && currentCycles >= nextKeyReadyAtCycles) {
                    var nextKey = -1
                    synchronized(wakeMonitor) {
                        if (pendingKeyPresses.isNotEmpty()) {
                            nextKey = pendingKeyPresses.removeFirst()
                        }
                    }
                    if (nextKey != -1) {
                        setKey(nextKey, true)
                        activeKeyCode = nextKey
                        keyDownAtCycles = currentCycles
                        releaseAtCycles = currentCycles + KEY_HOLD_CYCLES
                        justPressedKey = true
                    }
                }

                if (speedUp) {
                    runTimeSlice(FRAME_INTERVAL, true)
                    pendingNs = 0L
                    debtNs = 0L
                    if (nowNs - lastLcdCopyNs >= LCD_INTERVAL_NS) {
                        synchronized(lcdBufferEx) {
                            copyLcdBufferEx(lcdBufferEx)
                        }
                        lastLcdCopyNs = nowNs
                    }
                } else {
                    if (justPressedKey) {
                        // Immediately run 7ms on key release (~0.1ms on host CPU) so the next 256 Hz
                        // timer1 IRQ scans the aligned keypad row and queues beep audio right now.
                        val elapsedMs = (pendingNs / 1_000_000L).toInt()
                        val borrowMs = if (debtNs < 10_000_000L) (7 - elapsedMs).coerceAtLeast(0) else 0
                        val runMs = (elapsedMs + borrowMs).coerceAtLeast(1)
                        runTimeSlice(runMs, false)
                        pendingNs -= elapsedMs * 1_000_000L
                        debtNs += borrowMs * 1_000_000L
                        noRepayUntilNs = nowNs + 60_000_000L
                    } else {
                        if (debtNs > 0L && nowNs >= noRepayUntilNs && pendingNs >= 4_000_000L) {
                            pendingNs -= 1_000_000L
                            debtNs = (debtNs - 1_000_000L).coerceAtLeast(0L)
                        }
                        val sliceMs = (pendingNs / 1_000_000L).toInt()
                        if (sliceMs > 0) {
                            runTimeSlice(sliceMs, false)
                            pendingNs -= sliceMs * 1_000_000L
                        } else if (justReleasedKey && debtNs < 12_000_000L) {
                            runTimeSlice(1, false)
                            debtNs += 1_000_000L
                        }
                    }

                    val afterSliceNs = System.nanoTime()
                    if (afterSliceNs - lastLcdCopyNs >= LCD_INTERVAL_NS) {
                        synchronized(lcdBufferEx) {
                            copyLcdBufferEx(lcdBufferEx)
                        }
                        lastLcdCopyNs = afterSliceNs
                    }

                    val waitNs = SLICE_INTERVAL_NS - pendingNs - (afterSliceNs - nowNs)
                    if (waitNs > 200_000L && isRunning && !speedUp) {
                        val postSliceCycles = cycles
                        synchronized(wakeMonitor) {
                            val readyForImmediateKeyStep =
                                clearActiveKeyRequested ||
                                (pendingKeyPresses.isNotEmpty() && (
                                    (activeKeyCode == -1 && postSliceCycles >= nextKeyReadyAtCycles) ||
                                    (activeKeyCode != -1 && postSliceCycles - keyDownAtCycles >= MIN_KEY_HOLD_CYCLES)
                                ))
                            if (isRunning && !speedUp && !readyForImmediateKeyStep) {
                                wakeMonitor.wait(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                            }
                        }
                    }
                }
            }
            if (activeKeyCode != -1) {
                setKey(activeKeyCode, false)
                activeKeyCode = -1
            }
            if (saveStatesSetting) {
                save()
            }
        } finally {
            stopAudio()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return LayoutInflater.from(context).inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            speedUp = savedInstanceState.getBoolean(KEY_SPEED_UP, false)
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        emulatorContainer = view.findViewById(R.id.emulator_container)
        lcdContainer = view.findViewById(R.id.lcd_container)
        keypadLayout = view.findViewById(R.id.keypad_layout)
        lcdSurface = view.findViewById(R.id.lcd_surface)
        infoText = view.findViewById(R.id.info_text)
        keypadLayout.setOnButtonTouchListener(object : OnButtonPressedListener {
            override fun onKeyReleased(keyCode: Int) {
                enqueueKeyPress(keyCode)
            }
        })
        lcdSurface.holder.addCallback(this)
        view.doOnLayout {
            if (isAdded && this.view != null) {
                updateLcdLayoutSize()
            }
        }
        lcdContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                lcdContainer.post {
                    if (isAdded && this.view != null) {
                        updateLcdLayoutSize()
                    }
                }
            }
        }
        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        setupToolbar(toolbar)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val initialToolbarLeft = toolbar.paddingLeft
        val initialToolbarRight = toolbar.paddingRight
        val initialToolbarBottom = toolbar.paddingBottom
        val initialContainerLeft = emulatorContainer.paddingLeft
        val initialContainerTop = emulatorContainer.paddingTop
        val initialContainerRight = emulatorContainer.paddingRight
        val initialContainerBottom = emulatorContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(
                initialToolbarLeft + bars.left,
                bars.top,
                initialToolbarRight + bars.right,
                initialToolbarBottom
            )
            if (isLandscape) {
                emulatorContainer.setPadding(
                    initialContainerLeft + bars.left,
                    initialContainerTop,
                    initialContainerRight + bars.right,
                    initialContainerBottom + bars.bottom
                )
            } else {
                emulatorContainer.setPadding(
                    initialContainerLeft + bars.left,
                    initialContainerTop,
                    initialContainerRight + bars.right,
                    initialContainerBottom
                )
            }
            insets
        }
        initEmulation(forceReload = !sessionActive)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SPEED_UP, speedUp)
    }

    private fun updateLcdLayoutSize() {
        val rootView = view ?: return
        val containerWidth = lcdContainer.width
        if (containerWidth <= 0) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxLcdHeight = if (isLandscape) {
            (lcdContainer.height - infoText.height).coerceAtLeast(1)
        } else {
            (rootView.height - keypadLayout.height - infoText.height).coerceAtLeast(1)
        }
        val targetWidth = minOf(containerWidth, maxLcdHeight * 2)
        val targetHeight = targetWidth / 2
        if (targetWidth <= 0 || targetHeight <= 0) return
        displayScale = targetWidth.toFloat() / 160f
        lcdMatrix.setScale(displayScale, displayScale)
        val currentParams = lcdSurface.layoutParams as LinearLayout.LayoutParams
        if (currentParams.width != targetWidth || currentParams.height != targetHeight) {
            currentParams.width = targetWidth
            currentParams.height = targetHeight
            currentParams.gravity = Gravity.CENTER_HORIZONTAL
            lcdSurface.layoutParams = currentParams
            infoText.layoutParams = (infoText.layoutParams as LinearLayout.LayoutParams).apply {
                width = targetWidth
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun setupToolbar(toolbar: Toolbar) {
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.menu.findItem(R.id.action_speed_up)?.isChecked = speedUp
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_quit -> {
                    requireActivity().finish()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_restart -> {
                    clearQueuedKeys()
                    syncSystemTime()
                    reset()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_speed_up -> {
                    item.isChecked = !item.isChecked
                    speedUp = item.isChecked
                    setAudioEnabled(beepEnabledSetting && !speedUp)
                    wakeEmulationThread()
                    return@setOnMenuItemClickListener true
                }
                R.id.action_load -> {
                    clearQueuedKeys()
                    syncSystemTime()
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
                R.id.action_about -> {
                    Navigation.findNavController(toolbar).navigate(R.id.action_mainFragment_to_aboutFragment)
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
        if (activity?.isFinishing == true) {
            sessionActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activity?.isFinishing == true) {
            sessionActive = false
        }
        executorService.shutdown()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int,
                                height: Int) {
        if (width > 0 && height > 0) {
            displayScale = width.toFloat() / 160f
            lcdMatrix.setScale(displayScale, displayScale)
            updateLcd()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lcdMatrix.setScale(displayScale, displayScale)
        updateLcd()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}
    private fun initEmulation(forceReload: Boolean) {
        if (forceReload) {
            initDataFolder()
            val fileDir = requireContext().applicationContext.filesDir.absolutePath
            val romPath = "$fileDir/$ROM_FILE_NAME"
            val norPath = "$fileDir/$NOR_FILE_NAME"
            val statePath = "$fileDir/$STATE_FILE_NAME"
            initialize(romPath, norPath, statePath)
            syncSystemTime()
            load()
            sessionActive = true
        }
        synchronized(lcdBufferEx) {
            copyLcdBufferEx(lcdBufferEx)
        }
    }

    private fun syncSystemTime() {
        val cal = Calendar.getInstance()
        val tzOffsetSec = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000
        syncTime(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.DAY_OF_WEEK) - 1,
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            tzOffsetSec
        )
    }

    private fun startEmulation() {
        Choreographer.getInstance().postFrameCallback(this)
        isRunning = true
        runningFuture = executorService.submit(runnable)
    }

    private fun stopEmulation() {
        isRunning = false
        wakeEmulationThread()
        Choreographer.getInstance().removeFrameCallback(this)
        try {
            runningFuture?.get(1000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Timed out waiting for emulation thread to stop", e)
        }
        runningFuture = null
    }

    private fun enqueueKeyPress(keyCode: Int) {
        synchronized(wakeMonitor) {
            if (pendingKeyPresses.size < MAX_PENDING_KEYS) {
                pendingKeyPresses.addLast(keyCode)
            }
            wakeMonitor.notifyAll()
        }
    }

    private fun clearQueuedKeys() {
        synchronized(wakeMonitor) {
            pendingKeyPresses.clear()
            clearActiveKeyRequested = true
            wakeMonitor.notifyAll()
        }
    }

    private fun wakeEmulationThread() {
        synchronized(wakeMonitor) {
            wakeMonitor.notifyAll()
        }
    }

    private val saveStatesSetting: Boolean
        get() = preferences.getBoolean(SAVE_STATES_KEY, true)

    private val beepEnabledSetting: Boolean
        get() = preferences.getBoolean(ENABLE_BEEP_KEY, true)

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
        clearQueuedKeys()
        val filesDir = requireContext().applicationContext.filesDir.absolutePath
        val norFile = File("$filesDir/$NOR_FILE_NAME")
        val stateFile = File("$filesDir/$STATE_FILE_NAME")
        if (!norFile.delete()) {
            Log.e(TAG, "Error deleting nor file")
        }
        if (!stateFile.delete()) {
            Log.e(TAG, "Error deleting state file")
        }
        initEmulation(forceReload = true)
        reset()
    }

    private fun updateLcd() {
        val lcdCanvas = lcdSurface.holder.lockCanvas() ?: return
        synchronized(lcdBufferEx) { lcdBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(lcdBufferEx)) }
        lcdCanvas.drawColor(ContextCompat.getColor(requireContext(), R.color.lcd_background))
        lcdCanvas.drawBitmap(lcdBitmap, lcdMatrix, null)
        lcdSurface.holder.unlockCanvasAndPost(lcdCanvas)
    }

    private fun displayPerf() {
        frames++
        val now = System.currentTimeMillis()
        val elapse = now - lastFrameTime
        if (elapse > 1000L) {
            val fps = frames * 1000 / elapse
            val percentage = (cycles - lastCycles) * 100 / CYCLES_SECOND
            infoText.text = String.format(getString(R.string.perf_text), fps, cycles, percentage)
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
        @Volatile
        private var sessionActive = false
        private const val KEY_SPEED_UP = "speed_up"
        private const val FRAME_RATE = 60
        private const val FRAME_INTERVAL = 1000 / FRAME_RATE
        private const val SLICE_INTERVAL_NS = 3_000_000L
        private const val LCD_INTERVAL_NS = 16_666_667L
        private const val CYCLES_SECOND: Long = 5120000
        private const val CYCLES_MS: Long = CYCLES_SECOND / 1000
        private const val KEY_HOLD_CYCLES: Long = 45 * CYCLES_MS
        private const val MIN_KEY_HOLD_CYCLES: Long = 35 * CYCLES_MS
        private const val KEY_GAP_CYCLES: Long = 35 * CYCLES_MS
        private const val MAX_PENDING_KEYS = 16
        private const val ROM_FILE_NAME = "obj_lu.bin"
        private const val NOR_FILE_NAME = "nc1020.fls"
        private const val STATE_FILE_NAME = "nc1020.sts"
        private const val SAVE_STATES_KEY = "save_states"
        private const val ENABLE_BEEP_KEY = "enable_beep"
    }
}