package com.dopix.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Executes touch gestures via AccessibilityService.
 * Used for swipe navigation (Instagram Reels, TikTok, YouTube Shorts, etc.)
 */
class GestureExecutor(private val service: AccessibilityService) {

    private val TAG = "GestureExecutor"

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(it)
    }

    private val screenWidth: Float get() = metrics.widthPixels.toFloat()
    private val screenHeight: Float get() = metrics.heightPixels.toFloat()

    companion object {
        private const val SWIPE_DURATION_MS = 300L
        private const val SWIPE_HORIZONTAL_FRACTION = 0.5f  // horizontal center
        private const val SWIPE_TOP_FRACTION = 0.25f        // 25% from top
        private const val SWIPE_BOTTOM_FRACTION = 0.75f     // 75% from top
    }

    /**
     * Swipe up — go to next reel / short / video.
     */
    fun swipeUp(callback: AccessibilityService.GestureResultCallback? = null) {
        val cx = screenWidth * SWIPE_HORIZONTAL_FRACTION
        val startY = screenHeight * SWIPE_BOTTOM_FRACTION
        val endY = screenHeight * SWIPE_TOP_FRACTION

        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        dispatchGesture(path, SWIPE_DURATION_MS, callback)
        Log.d(TAG, "Swipe up: ($cx, $startY) -> ($cx, $endY)")
    }

    /**
     * Swipe down — go to previous reel / short / video.
     */
    fun swipeDown(callback: AccessibilityService.GestureResultCallback? = null) {
        val cx = screenWidth * SWIPE_HORIZONTAL_FRACTION
        val startY = screenHeight * SWIPE_TOP_FRACTION
        val endY = screenHeight * SWIPE_BOTTOM_FRACTION

        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        dispatchGesture(path, SWIPE_DURATION_MS, callback)
        Log.d(TAG, "Swipe down: ($cx, $startY) -> ($cx, $endY)")
    }

    /**
     * Swipe left — go to next page / story.
     */
    fun swipeLeft(callback: AccessibilityService.GestureResultCallback? = null) {
        val cy = screenHeight * SWIPE_HORIZONTAL_FRACTION
        val startX = screenWidth * 0.8f
        val endX = screenWidth * 0.2f

        val path = Path().apply {
            moveTo(startX, cy)
            lineTo(endX, cy)
        }
        dispatchGesture(path, SWIPE_DURATION_MS, callback)
        Log.d(TAG, "Swipe left")
    }

    /**
     * Swipe right — go to previous page / story.
     */
    fun swipeRight(callback: AccessibilityService.GestureResultCallback? = null) {
        val cy = screenHeight * SWIPE_HORIZONTAL_FRACTION
        val startX = screenWidth * 0.2f
        val endX = screenWidth * 0.8f

        val path = Path().apply {
            moveTo(startX, cy)
            lineTo(endX, cy)
        }
        dispatchGesture(path, SWIPE_DURATION_MS, callback)
        Log.d(TAG, "Swipe right")
    }

    /**
     * Tap at screen center.
     */
    fun tapCenter(callback: AccessibilityService.GestureResultCallback? = null) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        tap(cx, cy, callback)
    }

    /**
     * Tap at given coordinates.
     */
    fun tap(x: Float, y: Float, callback: AccessibilityService.GestureResultCallback? = null) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        dispatchGesture(path, 100L, callback)
        Log.d(TAG, "Tap at ($x, $y)")
    }

    private fun dispatchGesture(
        path: Path,
        duration: Long,
        callback: AccessibilityService.GestureResultCallback?
    ) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val result = if (callback != null) {
                service.dispatchGesture(gesture, callback, null)
            } else {
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Gesture completed")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture cancelled")
                    }
                }, null)
            }

            if (!result) {
                Log.e(TAG, "dispatchGesture returned false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching gesture: ${e.message}")
        }
    }
}
