package com.dopix.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dopix.app.DopixApplication
import com.dopix.app.DopixState
import com.dopix.app.MainActivity
import com.dopix.app.R
import com.dopix.app.ui.OverlayView
import com.dopix.app.utils.PreferencesManager

/**
 * Foreground service that manages the floating overlay button.
 * Uses WindowManager to display a draggable, transparent icon on top of all apps.
 */
class DopixOverlayService : Service() {

    companion object {
        private const val TAG = "DopixOverlayService"

        const val ACTION_START = "com.dopix.app.OVERLAY_START"
        const val ACTION_STOP = "com.dopix.app.OVERLAY_STOP"
        const val ACTION_SHOW = "com.dopix.app.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.dopix.app.OVERLAY_HIDE"

        fun startService(context: Context) {
            val intent = Intent(context, DopixOverlayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DopixOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private lateinit var prefs: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = PreferencesManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(DopixApplication.NOTIF_ID_OVERLAY, buildNotification())
                showOverlay()
            }
            ACTION_STOP -> {
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> removeOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overlay management
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return

        val view = OverlayView(this)
        val params = buildLayoutParams()

        view.windowX = prefs.overlayX
        view.windowY = prefs.overlayY
        params.x = view.windowX
        params.y = view.windowY

        view.setListener(object : OverlayView.OverlayInteractionListener {
            override fun onOverlayMoved(x: Int, y: Int) {
                params.x = x
                params.y = y
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating overlay position: ${e.message}")
                }
                // Save position
                prefs.overlayX = x
                prefs.overlayY = y
            }

            override fun onOverlayTapped() {
                handleOverlayTap()
            }
        })

        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay shown at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                view.destroy()
                windowManager.removeView(view)
                Log.d(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            }
            overlayView = null
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val sizePx = dpToPx(72)
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX
            y = prefs.overlayY
        }
    }

    private fun handleOverlayTap() {
        when (DopixState.mode.value) {
            DopixState.Mode.STOPPED -> {
                // Start Gemini service if API key available
                if (prefs.hasApiKey()) {
                    GeminiLiveService.startService(this)
                } else {
                    // Open main activity to configure
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }

            DopixState.Mode.SLEEPING -> {
                // Manual wake: tap to activate
                DopixState.setMode(DopixState.Mode.ACTIVE)
                Log.d(TAG, "Manually activated via overlay tap")
            }

            DopixState.Mode.ACTIVE -> {
                // Tap to go to sleep
                DopixState.setMode(DopixState.Mode.SLEEPING)
                Log.d(TAG, "Manually deactivated via overlay tap")
            }

            DopixState.Mode.CONNECTING -> {
                Log.d(TAG, "Connecting, tap ignored")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DopixApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setSmallIcon(R.drawable.ic_dopix)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
}
