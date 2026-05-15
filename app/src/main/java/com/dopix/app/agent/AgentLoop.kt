package com.dopix.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dopix.app.DopixState
import com.dopix.app.screen.ScreenCaptureManager
import com.dopix.app.screen.ScreenController
import com.dopix.app.screen.ScreenReader
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Agent Loop: Observe -> Think -> Act -> Repeat
 *
 * This is the core intelligence -- sends screen state to Gemini,
 * gets back structured actions, executes them, and loops until done.
 */
class AgentLoop(
    private val screenReader: ScreenReader,
    private val screenController: ScreenController,
    private val screenCapture: ScreenCaptureManager?,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_STEPS = 15
        private const val GEMINI_REST_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isRunning = false

    /**
     * Execute a complex multi-step command by observing the screen,
     * asking Gemini for actions, executing them, and repeating.
     */
    suspend fun executeCommand(
        userCommand: String,
        getRootNode: () -> AccessibilityNodeInfo?
    ): String {
        if (isRunning) return "Agent is already running a task"
        isRunning = true
        DopixState.setAgentRunning(true)

        var stepsExecuted = 0
        var lastResult = ""
        val conversationHistory = mutableListOf<JSONObject>()

        try {
            // Initial system context
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", buildSystemPrompt())
                    })
                })
            })

            while (stepsExecuted < MAX_STEPS && isRunning) {
                // OBSERVE: Read current screen
                val root = getRootNode()
                val elements = screenReader.readScreen(root)
                val screenText = screenReader.screenToText(elements)
                val chatMessages = screenReader.extractChatMessages(elements)
                root?.recycle()

                // Build observation message
                val observation = buildObservation(
                    userCommand = if (stepsExecuted == 0) userCommand else null,
                    screenText = screenText,
                    chatMessages = chatMessages,
                    previousResult = if (stepsExecuted > 0) lastResult else null,
                    step = stepsExecuted
                )

                conversationHistory.add(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", observation) })
                    })
                })

                // THINK: Ask Gemini what to do
                val response = callGeminiRest(conversationHistory)
                if (response == null) {
                    lastResult = "Gemini API error"
                    break
                }

                conversationHistory.add(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", response) })
                    })
                })

                // Parse actions from response
                val actions = parseActions(response)
                if (actions.isEmpty()) {
                    // No more actions = task complete
                    lastResult = extractConversationText(response)
                    break
                }

                // ACT: Execute each action
                for (action in actions) {
                    lastResult = executeAction(action, elements)
                    delay(500) // Brief pause between actions for UI to settle
                }

                stepsExecuted++
                delay(800) // Wait for screen to update after actions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop error", e)
            lastResult = "Error: ${e.message}"
        } finally {
            isRunning = false
            DopixState.setAgentRunning(false)
        }

        return lastResult
    }

    fun stop() {
        isRunning = false
    }

    private fun buildSystemPrompt(): String {
        return """You are Dopix, an AI agent that controls an Android phone screen.
You can see the screen content and execute actions.

Available actions (return as JSON array):
- {"action": "click", "x": 540, "y": 1200} -- click at screen coordinates
- {"action": "click_text", "text": "Send"} -- find text on screen and click it
- {"action": "click_element", "index": 5} -- click element by its # index from screen reading
- {"action": "long_press", "x": 540, "y": 1200} -- long press at coordinates
- {"action": "swipe", "direction": "up"|"down"|"left"|"right"} -- swipe screen
- {"action": "type", "text": "Hello!"} -- type text in focused/first input field
- {"action": "append", "text": " world"} -- append text to current input
- {"action": "press_enter"} -- press enter/send
- {"action": "send_message"} -- click send button
- {"action": "back"} -- press back button
- {"action": "home"} -- go to home screen
- {"action": "open_app", "app": "youtube"} -- open an app by name
- {"action": "scroll_down"} -- scroll down to see more
- {"action": "scroll_up"} -- scroll up to see previous
- {"action": "wait", "ms": 1000} -- wait for UI to update
- {"action": "done", "message": "Task completed!"} -- task is finished

RULES:
1. Respond ONLY with a JSON array of actions. Example: [{"action": "click_text", "text": "Search"}, {"action": "type", "text": "hello"}]
2. If the task is complete, use [{"action": "done", "message": "..."}]
3. If you want to speak to the user (conversation), use [{"action": "done", "message": "your response here"}]
4. Execute 1-3 actions per step maximum, then wait to observe the result
5. Read the screen content carefully -- element indices and coordinates are provided
6. For chat apps: read the messages, understand context, then type an appropriate reply
7. You understand Uzbek language commands
8. Be smart about finding UI elements -- buttons might have icons described in contentDescription"""
    }

    private fun buildObservation(
        userCommand: String?,
        screenText: String,
        chatMessages: List<ScreenReader.ChatMessage>,
        previousResult: String?,
        step: Int
    ): String {
        val sb = StringBuilder()

        if (userCommand != null) {
            sb.appendLine("USER COMMAND: $userCommand")
            sb.appendLine()
        }

        if (previousResult != null) {
            sb.appendLine("PREVIOUS ACTION RESULT: $previousResult")
            sb.appendLine()
        }

        sb.appendLine("STEP: $step / $MAX_STEPS")
        sb.appendLine()
        sb.appendLine(screenText)

        if (chatMessages.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== CHAT MESSAGES DETECTED ===")
            for (msg in chatMessages) {
                val side = if (msg.isOnRight) "ME" else "THEM"
                sb.appendLine("[$side]: ${msg.text}")
            }
        }

        return sb.toString()
    }

    private fun callGeminiRest(conversation: List<JSONObject>): String? {
        val body = JSONObject().apply {
            put("contents", JSONArray(conversation))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_REST_URL?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } else {
                Log.e(TAG, "Gemini REST error: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini REST call failed", e)
            null
        }
    }

    private fun parseActions(response: String): List<JSONObject> {
        return try {
            val cleaned = response.trim()
            val jsonStr = when {
                cleaned.startsWith("[") -> cleaned
                cleaned.contains("[") -> {
                    val start = cleaned.indexOf("[")
                    val end = cleaned.lastIndexOf("]") + 1
                    cleaned.substring(start, end)
                }
                else -> return emptyList()
            }
            val array = JSONArray(jsonStr)
            (0 until array.length()).map { array.getJSONObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse actions: $response", e)
            emptyList()
        }
    }

    private fun extractConversationText(response: String): String {
        return try {
            val actions = parseActions(response)
            actions.firstOrNull {
                it.optString("action") == "done"
            }?.optString("message", response) ?: response
        } catch (e: Exception) {
            response
        }
    }

    private suspend fun executeAction(
        action: JSONObject,
        elements: List<ScreenReader.ScreenElement>
    ): String {
        return when (action.optString("action")) {
            "click" -> {
                val x = action.optDouble("x", 540.0).toFloat()
                val y = action.optDouble("y", 1200.0).toFloat()
                if (screenController.clickAt(x, y)) "Clicked at ($x, $y)" else "Click failed"
            }

            "click_text" -> {
                val text = action.optString("text", "")
                if (screenController.clickOnText(text)) "Clicked on '$text'"
                else "Text '$text' not found"
            }

            "click_element" -> {
                val index = action.optInt("index", -1)
                if (screenController.clickElement(elements, index)) "Clicked element #$index"
                else "Element #$index not found"
            }

            "long_press" -> {
                val x = action.optDouble("x", 540.0).toFloat()
                val y = action.optDouble("y", 1200.0).toFloat()
                val duration = action.optLong("duration", 800)
                if (screenController.longPressAt(x, y, duration)) "Long pressed at ($x, $y)"
                else "Long press failed"
            }

            "swipe" -> {
                val dir = action.optString("direction", "up")
                val result = when (dir) {
                    "up" -> screenController.scrollDown()
                    "down" -> screenController.scrollUp()
                    "left" -> screenController.scrollLeft()
                    "right" -> screenController.scrollRight()
                    else -> false
                }
                if (result) "Swiped $dir" else "Swipe $dir failed"
            }

            "type" -> {
                val text = action.optString("text", "")
                if (screenController.typeText(text)) "Typed: $text"
                else "Type failed - no input field found"
            }

            "append" -> {
                val text = action.optString("text", "")
                if (screenController.appendText(text)) "Appended: $text"
                else "Append failed"
            }

            "press_enter" -> {
                if (screenController.pressEnter()) "Pressed enter" else "Enter failed"
            }

            "send_message" -> {
                if (screenController.clickSendButton()) "Send button clicked"
                else "Send button not found"
            }

            "back" -> {
                if (screenController.goBack()) "Went back" else "Back failed"
            }

            "home" -> {
                if (screenController.goHome()) "Went home" else "Home failed"
            }

            "open_app" -> {
                val app = action.optString("app", "")
                val context = screenController.service as Context
                AppLauncher(context).launchByKeyword(app)
                "Opened $app"
            }

            "scroll_down" -> {
                if (screenController.scrollDown()) "Scrolled down" else "Scroll failed"
            }

            "scroll_up" -> {
                if (screenController.scrollUp()) "Scrolled up" else "Scroll failed"
            }

            "wait" -> {
                val ms = action.optLong("ms", 1000)
                delay(ms)
                "Waited ${ms}ms"
            }

            "done" -> {
                val message = action.optString("message", "Task complete")
                isRunning = false
                message
            }

            else -> "Unknown action: ${action.optString("action")}"
        }
    }
}
