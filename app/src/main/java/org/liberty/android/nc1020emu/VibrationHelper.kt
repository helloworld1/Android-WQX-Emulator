package org.liberty.android.nc1020emu

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationHelper(context: Context) {
    private val vibrator: Vibrator?
    private val lowEffect: VibrationEffect?
    private val mediumEffect: VibrationEffect?
    private val highEffect: VibrationEffect?
    private val vibrationAttributes: VibrationAttributes?

    init {
        val appContext = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        lowEffect = createEffect(vibrator, LEVEL_LOW)
        mediumEffect = createEffect(vibrator, LEVEL_MEDIUM)
        highEffect = createEffect(vibrator, LEVEL_HIGH)

        vibrationAttributes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION)
        } else {
            null
        }
    }

    fun vibrate(level: String?) {
        if (level == null || level == LEVEL_OFF) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (level) {
                LEVEL_LOW -> lowEffect
                LEVEL_MEDIUM -> mediumEffect
                LEVEL_HIGH -> highEffect
                else -> null
            } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vibrationAttributes != null) {
                vib.vibrate(effect, vibrationAttributes)
            } else {
                vib.vibrate(effect)
            }
        } else {
            val durationMs = when (level) {
                LEVEL_LOW -> 12L
                LEVEL_MEDIUM -> 24L
                LEVEL_HIGH -> 42L
                else -> return
            }
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
    }

    companion object {
        const val PREF_KEY = "vibration"
        const val LEVEL_OFF = "off"
        const val LEVEL_LOW = "low"
        const val LEVEL_MEDIUM = "medium"
        const val LEVEL_HIGH = "high"

        private fun createEffect(
            vib: Vibrator?,
            level: String
        ): VibrationEffect? {
            if (vib == null || !vib.hasVibrator()) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

            return if (vib.hasAmplitudeControl()) {
                when (level) {
                    LEVEL_LOW -> VibrationEffect.createOneShot(14L, 100)
                    LEVEL_MEDIUM -> VibrationEffect.createOneShot(21L, 160)
                    LEVEL_HIGH -> VibrationEffect.createOneShot(27L, 255)
                    else -> null
                }
            } else {
                when (level) {
                    LEVEL_LOW -> VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
                    LEVEL_MEDIUM -> VibrationEffect.createOneShot(24L, VibrationEffect.DEFAULT_AMPLITUDE)
                    LEVEL_HIGH -> VibrationEffect.createOneShot(42L, VibrationEffect.DEFAULT_AMPLITUDE)
                    else -> null
                }
            }
        }
    }
}
