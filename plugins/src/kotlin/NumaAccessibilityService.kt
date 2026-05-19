package com.numa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class NumaAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW_TOAST = "com.numa.SHOW_TOAST"
        const val EXTRA_MESSAGE     = "message"
    }

    private lateinit var volumeKeyReceiver: VolumeKeyReceiver

    /**
     * Receiver pour afficher des toasts depuis NumaOperationService.
     * Les AccessibilityServices sont des contextes système — ils peuvent
     * afficher des toasts même sur Android 12+ où les services background ne le peuvent pas.
     */
    private val toastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return
            Handler(Looper.getMainLooper()).post {
                val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
                toast.show()
                Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Mise à jour dynamique du flag clavier (en plus de la config XML)
        try {
            serviceInfo?.let { info ->
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                serviceInfo = info
            }
        } catch (_: Exception) { /* ignore si serviceInfo non modifiable */ }

        volumeKeyReceiver = VolumeKeyReceiver(this)

        // Enregistrement du receiver de toasts
        val filter = IntentFilter(ACTION_SHOW_TOAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(toastReceiver, filter)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP   -> volumeKeyReceiver.onVolumeKey(isUp = true)
                KeyEvent.KEYCODE_VOLUME_DOWN -> volumeKeyReceiver.onVolumeKey(isUp = false)
            }
        }
        // Retourne false : le volume change normalement côté utilisateur
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(toastReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }
}
