package com.dopix.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton shared state between all Dopix services.
 * Thread-safe via StateFlow + atomic operations.
 */
object DopixState {

    enum class Mode {
        STOPPED,      // All services stopped
        SLEEPING,     // Services running but not processing (waiting for wake word)
        CONNECTING,   // WebSocket connecting to Gemini
        ACTIVE        // Fully active – listening + sending to Gemini
    }

    private val _mode = MutableStateFlow(Mode.STOPPED)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _lastAiResponse = MutableStateFlow("")
    val lastAiResponse: StateFlow<String> = _lastAiResponse.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _geminiConnected = MutableStateFlow(false)
    val geminiConnected: StateFlow<Boolean> = _geminiConnected.asStateFlow()

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    private val _lastAgentMessage = MutableStateFlow("")
    val lastAgentMessage: StateFlow<String> = _lastAgentMessage.asStateFlow()

    // Callbacks from services
    var onTranscriptReceived: ((String) -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null

    fun setMode(newMode: Mode) {
        _mode.value = newMode
    }

    fun setTranscript(text: String) {
        _lastTranscript.value = text
    }

    fun setAiResponse(text: String) {
        _lastAiResponse.value = text
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun setGeminiConnected(connected: Boolean) {
        _geminiConnected.value = connected
    }

    fun setAgentRunning(running: Boolean) {
        _agentRunning.value = running
    }

    fun setLastAgentMessage(msg: String) {
        _lastAgentMessage.value = msg
    }

    fun isActive(): Boolean = _mode.value == Mode.ACTIVE

    fun isSleeping(): Boolean = _mode.value == Mode.SLEEPING

    fun isRunning(): Boolean = _mode.value != Mode.STOPPED

    fun reset() {
        _mode.value = Mode.STOPPED
        _lastTranscript.value = ""
        _lastAiResponse.value = ""
        _geminiConnected.value = false
        _agentRunning.value = false
        _lastAgentMessage.value = ""
        onTranscriptReceived = null
        onCommandReceived = null
    }
}
