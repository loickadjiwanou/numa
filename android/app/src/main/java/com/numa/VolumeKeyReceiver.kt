package com.numa

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VolumeKeyReceiver(private val context: Context) {

    companion object {
        // true = Vol+, false = Vol-
        val PANIC_SEQUENCE   = listOf(true, false, true, true)
        val RESTORE_SEQUENCE = listOf(false, true, false, false)

        private const val MAX_AGE_MS   = 3000L  // fenêtre de détection : 3 secondes
        private const val DEBOUNCE_MS  = 200L   // délai minimum entre 2 pressions
    }

    private val history = ArrayDeque<Pair<Boolean, Long>>() // (isUp, timestamp)

    /**
     * Appelée par NumaAccessibilityService à chaque pression.
     * @param isUp  true = Volume+, false = Volume-
     */
    fun onVolumeKey(isUp: Boolean) {
        val now = System.currentTimeMillis()

        // Debounce : ignore les pressions trop rapprochées
        val last = history.lastOrNull()
        if (last != null && now - last.second < DEBOUNCE_MS) return

        // Purge les entrées trop anciennes
        history.removeAll { now - it.second > MAX_AGE_MS }

        history.addLast(Pair(isUp, now))

        checkSequences()
    }

    private fun checkSequences() {
        val keys = history.map { it.first }

        if (endsWith(keys, PANIC_SEQUENCE)) {
            history.clear()
            vibratePanic()
            NumaOperationService.startPanic(context)
            return
        }

        if (endsWith(keys, RESTORE_SEQUENCE)) {
            history.clear()
            vibrateRestore()
            NumaOperationService.startRestore(context)
        }
    }

    private fun endsWith(list: List<Boolean>, suffix: List<Boolean>): Boolean {
        if (list.size < suffix.size) return false
        return list.takeLast(suffix.size) == suffix
    }

    // ─── Feedback haptique ───────────────────────────────────────────────────

    private fun vibrate(pattern: LongArray, amplitudes: IntArray? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val v = vm.defaultVibrator
            if (amplitudes != null) {
                v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (amplitudes != null) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
                } else {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    /** PANIC : double vibration courte — 100ms, pause 50ms, 100ms */
    private fun vibratePanic() {
        // pattern : [délai, vibration, pause, vibration]
        vibrate(longArrayOf(0, 100, 50, 100))
    }

    /** RESTORE : une vibration longue — 300ms */
    private fun vibrateRestore() {
        vibrate(longArrayOf(0, 300))
    }

    /** Succès : 3 vibrations courtes */
    fun vibrateSuccess() {
        vibrate(longArrayOf(0, 80, 60, 80, 60, 80))
    }

    /** Erreur : 1 vibration longue forte */
    fun vibrateError() {
        vibrate(
            longArrayOf(0, 600),
            intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}
