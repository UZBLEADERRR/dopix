package com.dopix.app.agent

import android.content.Context
import android.util.Log
import com.dopix.app.services.DopixAccessibilityService
import com.dopix.app.utils.PreferencesManager
import org.json.JSONException
import org.json.JSONObject

/**
 * Processes AI responses from Gemini and executes the appropriate action.
 *
 * Expected JSON format from Gemini:
 * {
 *   "action": "type_text" | "swipe_next" | "swipe_prev" | "open_app" | "conversation",
 *   "text": "...",     // for type_text or conversation response
 *   "app": "..."       // for open_app
 * }
 */
class CommandProcessor(private val context: Context) {

    private val TAG = "CommandProcessor"
    private val appLauncher = AppLauncher(context)
    private val prefs = PreferencesManager.getInstance(context)

    sealed class CommandResult {
        object Success : CommandResult()
        object NoAccessibility : CommandResult()
        data class Error(val message: String) : CommandResult()
        data class Conversation(val text: String) : CommandResult()
    }

    /**
     * Parse and execute a command from Gemini's JSON response.
     */
    fun processGeminiResponse(jsonResponse: String): CommandResult {
        Log.d(TAG, "Processing response: $jsonResponse")

        // Try to extract JSON from response (Gemini may wrap it in markdown)
        val jsonStr = extractJson(jsonResponse) ?: return CommandResult.Conversation(jsonResponse)

        return try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action", "conversation")

            when (action) {
                "type_text" -> {
                    val text = json.optString("text", "")
                    if (text.isBlank()) {
                        CommandResult.Error("Empty text for type_text action")
                    } else {
                        typeText(text)
                    }
                }

                "swipe_next" -> {
                    swipeNext()
                }

                "swipe_prev" -> {
                    swipePrev()
                }

                "open_app" -> {
                    val app = json.optString("app", "")
                    val text = json.optString("text", app)
                    if (app.isBlank() && text.isBlank()) {
                        CommandResult.Error("No app specified")
                    } else {
                        openApp(if (app.isNotBlank()) app else text)
                    }
                }

                "agent_task" -> {
                    val task = json.optString("task", "")
                    if (task.isBlank()) {
                        CommandResult.Error("Empty task for agent_task action")
                    } else {
                        executeAgentTask(task)
                    }
                }

                "read_screen" -> {
                    readScreen()
                }

                "read_chat" -> {
                    val reply = json.optString("reply", "")
                    readChatAndReply(reply)
                }

                "click_at" -> {
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    if (x < 0 || y < 0) {
                        CommandResult.Error("Invalid coordinates for click_at")
                    } else {
                        clickAtCoordinates(x, y)
                    }
                }

                "conversation" -> {
                    val text = json.optString("text", "")
                    CommandResult.Conversation(text)
                }

                else -> {
                    Log.w(TAG, "Unknown action: $action, treating as conversation")
                    val text = json.optString("text", jsonResponse)
                    CommandResult.Conversation(text)
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Not valid JSON, treating as conversation: ${e.message}")
            CommandResult.Conversation(jsonResponse)
        }
    }

    /**
     * Process plain text commands (without JSON – fallback or wake word responses).
     */
    fun processTextCommand(text: String): CommandResult {
        val lower = text.lowercase().trim()

        // Navigation commands (Uzbek)
        if (lower.contains("keyingisi") || lower.contains("keyingiga") || lower.contains("next")) {
            return swipeNext()
        }
        if (lower.contains("oldingisi") || lower.contains("oldingi") || lower.contains("prev")) {
            return swipePrev()
        }

        // App open commands
        if (lower.contains("och") || lower.contains("open") || lower.contains("ishga tushir")) {
            val appName = extractAppName(lower)
            if (appName != null) {
                return openApp(appName)
            }
        }

        // Type text into focused input
        val accessibility = DopixAccessibilityService.instance
        if (accessibility != null && accessibility.hasFocusedInput()) {
            return typeText(text)
        }

        // Default: conversation
        return CommandResult.Conversation(text)
    }

    private fun typeText(text: String): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            accessibility.typeIntoFocusedInput(text)
            Log.d(TAG, "Typed text: $text")
            CommandResult.Success
        } else {
            Log.w(TAG, "Accessibility service not connected")
            CommandResult.NoAccessibility
        }
    }

    private fun swipeNext(): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            accessibility.swipeUp()
            Log.d(TAG, "Swiped up (next)")
            CommandResult.Success
        } else {
            Log.w(TAG, "Accessibility service not connected for swipe")
            CommandResult.NoAccessibility
        }
    }

    private fun swipePrev(): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            accessibility.swipeDown()
            Log.d(TAG, "Swiped down (prev)")
            CommandResult.Success
        } else {
            Log.w(TAG, "Accessibility service not connected for swipe")
            CommandResult.NoAccessibility
        }
    }

    private fun openApp(appKeyword: String): CommandResult {
        val launched = appLauncher.launchByKeyword(appKeyword)
        return if (launched) {
            Log.d(TAG, "Opened app: $appKeyword")
            CommandResult.Success
        } else {
            CommandResult.Error("Could not find app: $appKeyword")
        }
    }

    // -------------------------------------------------------------------------
    // Agent-level commands
    // -------------------------------------------------------------------------

    private fun executeAgentTask(task: String): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            val apiKey = prefs.apiKey
            if (apiKey.isBlank()) {
                CommandResult.Error("No API key configured for agent task")
            } else {
                accessibility.executeAgentCommand(task, apiKey)
                Log.d(TAG, "Agent task started: $task")
                CommandResult.Conversation("Starting task: $task")
            }
        } else {
            CommandResult.NoAccessibility
        }
    }

    private fun readScreen(): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            val content = accessibility.readScreenContent()
            Log.d(TAG, "Screen read: ${content.take(200)}...")
            CommandResult.Conversation(content)
        } else {
            CommandResult.NoAccessibility
        }
    }

    private fun readChatAndReply(reply: String): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            val messages = accessibility.readChatMessages()
            val chatText = messages.joinToString("\n") { msg ->
                val side = if (msg.isOnRight) "ME" else "THEM"
                "[$side]: ${msg.text}"
            }
            if (reply.isNotBlank()) {
                // Type the reply into the input field and send
                accessibility.typeIntoFocusedInput(reply)
                Log.d(TAG, "Chat reply typed: $reply")
            }
            Log.d(TAG, "Chat messages read: ${messages.size}")
            CommandResult.Conversation(if (chatText.isNotBlank()) chatText else "No chat messages found on screen")
        } else {
            CommandResult.NoAccessibility
        }
    }

    private fun clickAtCoordinates(x: Float, y: Float): CommandResult {
        val accessibility = DopixAccessibilityService.instance
        return if (accessibility != null) {
            accessibility.clickAt(x, y)
            Log.d(TAG, "Clicked at ($x, $y)")
            CommandResult.Success
        } else {
            CommandResult.NoAccessibility
        }
    }

    /**
     * Extract JSON object from a string that may contain markdown code blocks.
     */
    private fun extractJson(text: String): String? {
        // Try raw JSON first
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return trimmed

        // Try markdown code block: ```json ... ``` or ``` ... ```
        val jsonBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonBlockRegex.find(trimmed)
        if (match != null) return match.groupValues[1]

        // Try to find any JSON object in text
        val jsonInlineRegex = Regex("\\{[^{}]*\"action\"[^{}]*\\}")
        val inline = jsonInlineRegex.find(trimmed)
        if (inline != null) return inline.value

        return null
    }

    private fun extractAppName(text: String): String? {
        // Patterns like "youtubeni och", "instagram och", "open youtube"
        val patterns = listOf(
            Regex("(\\w+)ni och"),
            Regex("(\\w+)\\s+och"),
            Regex("open\\s+(\\w+)"),
            Regex("(\\w+)\\s+oching"),
            Regex("ishga tushir\\s+(\\w+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = match.groupValues[1]
                if (candidate.length > 2) return candidate
            }
        }
        return null
    }
}
