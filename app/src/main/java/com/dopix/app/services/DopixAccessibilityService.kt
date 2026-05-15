package com.dopix.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dopix.app.DopixState
import com.dopix.app.agent.AgentLoop
import com.dopix.app.agent.GestureExecutor
import com.dopix.app.screen.ScreenController
import com.dopix.app.screen.ScreenReader
import com.dopix.app.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Accessibility service that:
 *  - Detects focused input fields across all apps
 *  - Injects text into them
 *  - Executes touch gestures (swipe for Reels, etc.)
 */
class DopixAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DopixA11y"

        // Singleton reference for use by CommandProcessor
        @Volatile
        var instance: DopixAccessibilityService? = null
    }

    private var gestureExecutor: GestureExecutor? = null
    private var lastFocusedNodeInfo: AccessibilityNodeInfo? = null

    // Screen control components
    val screenReader = ScreenReader()
    lateinit var screenController: ScreenController
        private set
    private var agentLoop: AgentLoop? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        gestureExecutor = GestureExecutor(this)
        screenController = ScreenController(this)
        DopixState.setAccessibilityConnected(true)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source ?: return
                if (isInputField(node)) {
                    lastFocusedNodeInfo = AccessibilityNodeInfo.obtain(node)
                    Log.d(TAG, "Input focused: class=${node.className}, pkg=${event.packageName}")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Refresh focused node on window changes
                refreshFocusedNode()
            }

            else -> { /* ignore */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        DopixState.setAccessibilityConnected(false)
        lastFocusedNodeInfo?.recycle()
        lastFocusedNodeInfo = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if there is a currently focused input field.
     */
    fun hasFocusedInput(): Boolean = findFocusedInputNode() != null

    /**
     * Find the currently focused input field in the active window.
     */
    fun findFocusedInputNode(): AccessibilityNodeInfo? {
        // Try input focus first (cursor focus)
        val root = rootInActiveWindow ?: return null
        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && isInputField(inputFocused)) {
            return inputFocused
        }

        // Fall back to cached last known focused node
        val cached = lastFocusedNodeInfo
        if (cached != null && cached.isFocused && isInputField(cached)) {
            return cached
        }

        // BFS search for any focusable input
        return findFirstInputInTree(root)
    }

    /**
     * Type text into the currently focused input field.
     */
    fun typeIntoFocusedInput(text: String): Boolean {
        val node = findFocusedInputNode() ?: run {
            Log.w(TAG, "No focused input node to type into")
            return false
        }

        return try {
            val args = Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "typeIntoFocusedInput result=$result text=$text")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error typing text: ${e.message}")
            false
        }
    }

    /**
     * Append text to the currently focused input (paste-style).
     */
    fun appendToFocusedInput(text: String): Boolean {
        val node = findFocusedInputNode() ?: return false

        return try {
            // First move cursor to end
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

            val existing = node.text?.toString() ?: ""
            val newText = if (existing.isBlank()) text else "$existing $text"

            val args = Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "Error appending text: ${e.message}")
            false
        }
    }

    /**
     * Swipe up (next reel / TikTok / YouTube Short).
     */
    fun swipeUp() {
        gestureExecutor?.swipeUp() ?: fallbackSwipeUp()
    }

    /**
     * Swipe down (previous reel).
     */
    fun swipeDown() {
        gestureExecutor?.swipeDown() ?: fallbackSwipeDown()
    }

    /**
     * Swipe left.
     */
    fun swipeLeft() {
        gestureExecutor?.swipeLeft()
    }

    /**
     * Swipe right.
     */
    fun swipeRight() {
        gestureExecutor?.swipeRight()
    }

    /**
     * Tap at screen center (useful for play/pause).
     */
    fun tapCenter() {
        gestureExecutor?.tapCenter()
    }

    // -------------------------------------------------------------------------
    // Screen reading & agent control
    // -------------------------------------------------------------------------

    /**
     * Get the root accessibility node for the active window.
     */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Read all visible text on the current screen as a structured string.
     */
    fun readScreenContent(): String {
        val root = rootInActiveWindow ?: return ""
        val elements = screenReader.readScreen(root)
        val text = screenReader.screenToText(elements)
        root.recycle()
        return text
    }

    /**
     * Read chat messages from the current screen.
     */
    fun readChatMessages(): List<ScreenReader.ChatMessage> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = screenReader.readScreen(root)
        val messages = screenReader.extractChatMessages(elements)
        root.recycle()
        return messages
    }

    /**
     * Execute a complex multi-step agent command via Gemini.
     */
    fun executeAgentCommand(command: String, apiKey: String) {
        if (agentLoop == null) {
            agentLoop = AgentLoop(screenReader, screenController, null, apiKey)
        }
        serviceScope.launch {
            val result = agentLoop!!.executeCommand(command) { rootInActiveWindow }
            DopixState.setLastAgentMessage(result)
        }
    }

    /**
     * Stop the currently running agent loop.
     */
    fun stopAgent() {
        agentLoop?.stop()
    }

    /**
     * Click at specific screen coordinates via ScreenController.
     */
    fun clickAt(x: Float, y: Float) {
        serviceScope.launch {
            screenController.clickAt(x, y)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun isInputField(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false
        return node.isEditable ||
                className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) ||
                className.contains("MultiAutoCompleteTextView", ignoreCase = true) ||
                className.contains("TextInputEditText", ignoreCase = true) ||
                (node.isClickable && node.isFocusable && node.isEnabled &&
                        (className.contains("Text", ignoreCase = true) ||
                                className.contains("Input", ignoreCase = true)))
    }

    private fun refreshFocusedNode() {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (isInputField(focused)) {
            lastFocusedNodeInfo?.recycle()
            lastFocusedNodeInfo = AccessibilityNodeInfo.obtain(focused)
        }
    }

    private fun findFirstInputInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isInputField(node) && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstInputInTree(child)
            if (found != null) return found
        }
        return null
    }

    private fun fallbackSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, h * 0.75f)
            lineTo(w / 2f, h * 0.25f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun fallbackSwipeDown() {
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, h * 0.25f)
            lineTo(w / 2f, h * 0.75f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
