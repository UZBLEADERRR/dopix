package com.dopix.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Executes all types of screen actions via the AccessibilityService.
 * Provides coordinate-based clicking, swiping, text entry, navigation,
 * and element-based interactions.
 */
class ScreenController(val service: AccessibilityService) {

    companion object {
        private const val TAG = "ScreenController"
    }

    // Click at exact screen coordinates
    suspend fun clickAt(x: Float, y: Float): Boolean {
        Log.d(TAG, "Clicking at ($x, $y)")
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGestureAsync(gesture)
    }

    // Long press at coordinates
    suspend fun longPressAt(x: Float, y: Float, durationMs: Long = 800): Boolean {
        Log.d(TAG, "Long pressing at ($x, $y) for ${durationMs}ms")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureAsync(gesture)
    }

    // Swipe from point to point
    suspend fun swipe(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 300
    ): Boolean {
        Log.d(TAG, "Swiping from ($fromX,$fromY) to ($toX,$toY)")
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureAsync(gesture)
    }

    // Scroll down
    suspend fun scrollDown(): Boolean = swipe(540f, 1400f, 540f, 600f, 400)

    // Scroll up
    suspend fun scrollUp(): Boolean = swipe(540f, 600f, 540f, 1400f, 400)

    // Scroll left (next page)
    suspend fun scrollLeft(): Boolean = swipe(900f, 1000f, 100f, 1000f, 300)

    // Scroll right (prev page)
    suspend fun scrollRight(): Boolean = swipe(100f, 1000f, 900f, 1000f, 300)

    // Navigate back
    fun goBack(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    // Navigate home
    fun goHome(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    // Open recent apps
    fun openRecents(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    // Open notifications
    fun openNotifications(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    // Open quick settings
    fun openQuickSettings(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    // Find a node by text and click it
    suspend fun clickOnText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) {
            root.recycle()
            return false
        }
        val target = nodes.first()
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        nodes.forEach { it.recycle() }
        root.recycle()
        return clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    // Find an input field and type text into it
    fun typeText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val inputNode = findFocusedInput(root) ?: findFirstInput(root)
        if (inputNode == null) {
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val result = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        inputNode.recycle()
        root.recycle()
        return result
    }

    // Append text to existing input (don't overwrite)
    fun appendText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val inputNode = findFocusedInput(root) ?: findFirstInput(root)
        if (inputNode == null) {
            root.recycle()
            return false
        }
        val existing = inputNode.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + text
            )
        }
        val result = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        inputNode.recycle()
        root.recycle()
        return result
    }

    // Click on a specific element by index from ScreenReader output
    suspend fun clickElement(
        elements: List<ScreenReader.ScreenElement>,
        index: Int
    ): Boolean {
        if (index < 0 || index >= elements.size) return false
        val el = elements[index]
        return clickAt(el.bounds.centerX().toFloat(), el.bounds.centerY().toFloat())
    }

    // Find focused input field
    private fun findFocusedInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    // Find first editable input on screen
    private fun findFirstInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFirstInput(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    // Press enter/send button - useful for sending messages
    fun pressEnter(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val inputNode = findFocusedInput(root)
        if (inputNode != null) {
            val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                inputNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            inputNode.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    // Find and click send button (common patterns in messaging apps)
    suspend fun clickSendButton(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val sendTexts = listOf("Send", "Yuborish", "Jo'natish", "send", "Send message")
        for (text in sendTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                val bounds = Rect()
                nodes.first().getBoundsInScreen(bounds)
                nodes.forEach { it.recycle() }
                root.recycle()
                return clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }
        root.recycle()
        return pressEnter()
    }

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }
}
