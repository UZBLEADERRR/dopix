package com.dopix.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Launches Android apps by package name or app keyword.
 */
class AppLauncher(private val context: Context) {

    private val TAG = "AppLauncher"

    companion object {
        // Mapping of Uzbek/common keywords to package names
        private val APP_MAP = mapOf(
            "youtube" to "com.google.android.youtube",
            "youtubeni" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "instagramni" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "spotify" to "com.spotify.music",
            "maps" to "com.google.android.apps.maps",
            "xarita" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "sozlamalar" to "com.android.settings",
            "kamera" to "android.media.action.IMAGE_CAPTURE",
            "camera" to "android.media.action.IMAGE_CAPTURE",
            "telefon" to "com.android.dialer",
            "phone" to "com.android.dialer",
            "sms" to "com.android.mms",
            "xabar" to "com.android.mms",
            "calculator" to "com.android.calculator2",
            "kalkulyator" to "com.android.calculator2",
        )
    }

    /**
     * Launch an app by keyword extracted from command.
     * Returns true if launch was attempted.
     */
    fun launchByKeyword(keyword: String): Boolean {
        val lower = keyword.lowercase().trim()

        // Try direct map lookup
        for ((key, pkg) in APP_MAP) {
            if (lower.contains(key)) {
                return launchPackage(pkg)
            }
        }

        // Try to find by app name search
        return launchByAppName(lower)
    }

    /**
     * Launch app by exact package name.
     */
    fun launchPackage(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Launched package: $packageName")
                true
            } else {
                Log.w(TAG, "Package not installed: $packageName")
                // Try Play Store
                openPlayStore(packageName)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching $packageName: ${e.message}")
            false
        }
    }

    /**
     * Search installed apps by label name.
     */
    fun launchByAppName(appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val match = apps.firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains(appName) || appName.contains(label)
            }

            if (match != null) {
                launchPackage(match.packageName)
            } else {
                Log.w(TAG, "No app found for: $appName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching apps: ${e.message}")
            false
        }
    }

    private fun openPlayStore(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Play store not available
        }
    }
}
