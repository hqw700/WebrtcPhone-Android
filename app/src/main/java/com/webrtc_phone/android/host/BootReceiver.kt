package com.webrtc_phone.android.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (HostConfig.isAutoStartEnabled(context)) {
                    Log.i(TAG, "Auto-start enabled, launching AgentHostService...")
                    try {
                        AgentHostService.start(context.applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start AgentHostService on boot", e)
                    }
                } else {
                    Log.d(TAG, "Auto-start disabled, skipping launch.")
                }
            }
        }
    }
}
