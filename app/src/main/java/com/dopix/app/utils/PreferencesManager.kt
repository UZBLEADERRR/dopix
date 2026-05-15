package com.dopix.app.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "dopix_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_WAKE_ENERGY_THRESHOLD = "wake_energy_threshold"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, 16)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, 200)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var wakeEnergyThreshold: Float
        get() = prefs.getFloat(KEY_WAKE_ENERGY_THRESHOLD, 800f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_ENERGY_THRESHOLD, value).apply()

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun clear() = prefs.edit().clear().apply()
}
