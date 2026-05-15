package com.dopix.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dopix.app.utils.PreferencesManager

class DopixApplication : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "dopix_service_channel"
        const val CHANNEL_ID_ALERTS = "dopix_alerts_channel"
        const val NOTIF_ID_OVERLAY = 1001
        const val NOTIF_ID_GEMINI = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Initialize preferences singleton
        PreferencesManager.getInstance(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Dopix Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background services for Dopix assistant"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Dopix Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts and notifications from Dopix"
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }
}
