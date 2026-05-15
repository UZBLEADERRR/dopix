package com.dopix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dopix.app.services.DopixOverlayService
import com.dopix.app.services.GeminiLiveService
import com.dopix.app.utils.PreferencesManager

/**
 * Starts Dopix services on device boot if auto-start is configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PreferencesManager.getInstance(context)
        if (!prefs.autoStart || !prefs.hasApiKey()) {
            Log.d("BootReceiver", "Auto-start disabled or no API key, skipping")
            return
        }

        Log.d("BootReceiver", "Boot completed, starting Dopix services")

        try {
            DopixOverlayService.startService(context)
            GeminiLiveService.startService(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start services on boot: ${e.message}")
        }
    }
}
