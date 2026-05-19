package com.numa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NumaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            NumaService.ACTION_RESTART -> {
                NumaService.start(context)
            }
        }
    }
}
