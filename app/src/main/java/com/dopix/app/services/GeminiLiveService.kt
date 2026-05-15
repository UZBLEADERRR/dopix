package com.dopix.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dopix.app.DopixApplication
import com.dopix.app.DopixState
import com.dopix.app.MainActivity
import com.dopix.app.R
import com.dopix.app.agent.CommandProcessor
import com.dopix.app.audio.AudioCaptureManager
import com.dopix.app.audio.WakeWordDetector
import com.dopix.app.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that:
 *  1. Opens a WebSocket to Gemini Multimodal Live API
 *  2. Captures audio via AudioCaptureManager
 *  3. Streams audio when ACTIVE, pauses when SLEEPING
 *  4. Processes Gemini responses → CommandProcessor
 *  5. Handles wake/sleep word detection
 */
class GeminiLiveService : Service() {

    companion object {
        private const val TAG = "GeminiLiveService"
        const val ACTION_START = "com.dopix.app.GEMINI_START"
        const val ACTION_STOP = "com.dopix.app.GEMINI_STOP"

        private const val GEMINI_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val GEMINI_MODEL = "models/gemini-2.0-flash-exp"

        // How many chunks to accumulate during SLEEPING before sending for wake-word check
        private const val SLEEP_CHUNK_ACCUMULATE = 8
        // If RMS stays below this for SLEEP_SILENCE_COUNT chunks, discard accumulated buffer
        private const val SLEEP_SILENCE_COUNT = 3

        fun startService(context: Context) {
            val intent = Intent(context, GeminiLiveService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GeminiLiveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wsJob: Job? = null
    private var audioManager: AudioCaptureManager? = null
    private var webSocket: WebSocket? = null
    private val wakeWordDetector = WakeWordDetector()
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var prefs: PreferencesManager

    private val isSetupSent = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    // Sleeping-mode accumulation
    private val sleepBuffer = mutableListOf<ByteArray>()
    private var silenceCount = 0

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        commandProcessor = CommandProcessor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startGemini()
            ACTION_STOP -> stopGemini()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopGemini()
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private fun startGemini() {
        startForeground(DopixApplication.NOTIF_ID_GEMINI, buildNotification())
        DopixState.setMode(DopixState.Mode.CONNECTING)

        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "No API key configured")
            DopixState.setMode(DopixState.Mode.STOPPED)
            stopSelf()
            return
        }

        // Start audio capture
        audioManager = AudioCaptureManager(this).also { am ->
            am.setCallback(object : AudioCaptureManager.AudioCallback {
                override fun onAudioChunk(pcmData: ByteArray, rms: Double) {
                    handleAudioChunk(pcmData, rms)
                }
            })
            am.start()
        }

        // Connect WebSocket
        wsJob = serviceScope.launch {
            connectWebSocket(apiKey)
        }
    }

    private fun stopGemini() {
        Log.d(TAG, "Stopping Gemini service")
        isSetupSent.set(false)
        isConnected.set(false)

        wsJob?.cancel()
        wsJob = null

        try {
            webSocket?.close(1000, "Service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket: ${e.message}")
        }
        webSocket = null

        audioManager?.stop()
        audioManager = null

        DopixState.setMode(DopixState.Mode.STOPPED)
        DopixState.setGeminiConnected(false)

        sleepBuffer.clear()
        silenceCount = 0

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Audio handling
    // -------------------------------------------------------------------------

    private fun handleAudioChunk(pcmData: ByteArray, rms: Double) {
        when (DopixState.mode.value) {
            DopixState.Mode.ACTIVE -> {
                // Send audio directly to Gemini
                if (isConnected.get() && isSetupSent.get()) {
                    sendAudioChunk(pcmData)
                }
            }

            DopixState.Mode.SLEEPING -> {
                // Only process if voice activity detected
                val hasVoice = wakeWordDetector.hasVoiceActivity(
                    pcmData,
                    prefs.wakeEnergyThreshold.toDouble()
                )

                if (hasVoice) {
                    silenceCount = 0
                    sleepBuffer.add(pcmData)

                    // Once we have enough chunks, send briefly to check for wake word
                    if (sleepBuffer.size >= SLEEP_CHUNK_ACCUMULATE) {
                        flushSleepBufferForWakeCheck()
                    }
                } else {
                    silenceCount++
                    if (silenceCount >= SLEEP_SILENCE_COUNT) {
                        sleepBuffer.clear()
                        silenceCount = 0
                    }
                }
            }

            DopixState.Mode.CONNECTING -> {
                // Buffer a small amount while connecting
                if (sleepBuffer.size < 20) sleepBuffer.add(pcmData)
            }

            DopixState.Mode.STOPPED -> { /* ignore */ }
        }
    }

    private fun flushSleepBufferForWakeCheck() {
        if (!isConnected.get() || !isSetupSent.get()) {
            sleepBuffer.clear()
            return
        }

        Log.d(TAG, "Sending ${sleepBuffer.size} sleep-mode chunks for wake word check")
        val chunks = sleepBuffer.toList()
        sleepBuffer.clear()
        silenceCount = 0

        // Send all accumulated chunks
        for (chunk in chunks) {
            sendAudioChunk(chunk)
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket
    // -------------------------------------------------------------------------

    private fun connectWebSocket(apiKey: String) {
        val url = "$GEMINI_WS_URL?key=$apiKey"

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout for streaming
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Connecting to Gemini Live API...")
        webSocket = client.newWebSocket(request, geminiListener)
    }

    private val geminiListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            this@GeminiLiveService.webSocket = webSocket
            isConnected.set(true)
            DopixState.setGeminiConnected(true)

            // Send setup message
            sendSetupMessage(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WS message: ${text.take(300)}")
            handleGeminiMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            isConnected.set(false)
            isSetupSent.set(false)
            DopixState.setGeminiConnected(false)

            if (DopixState.isRunning()) {
                // Auto-reconnect after delay
                serviceScope.launch {
                    delay(3000)
                    if (DopixState.isRunning()) {
                        val apiKey = prefs.apiKey
                        if (apiKey.isNotBlank()) {
                            Log.d(TAG, "Attempting reconnect...")
                            connectWebSocket(apiKey)
                        }
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
            isConnected.set(false)
            isSetupSent.set(false)
            DopixState.setGeminiConnected(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            isConnected.set(false)
            isSetupSent.set(false)
            DopixState.setGeminiConnected(false)
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val systemInstruction = """
            You are Dopix, a voice assistant that controls Android devices.
            The user speaks in Uzbek or English.

            When the user speaks a command, respond ONLY with a JSON object (no markdown, no extra text):
            {"action": "ACTION", "text": "TEXT", "app": "APP_NAME"}

            Actions:
            - "type_text": User wants to type something. Put transcribed text in "text" field.
            - "swipe_next": User says "keyingisi", "next", "oldingisi emas keyingisi" → go to next content
            - "swipe_prev": User says "oldingisi", "previous", "orqaga" → go to previous content
            - "open_app": User says "X och", "X ni och", "open X", "X ishga tushir" → open app. Put app name in "app" field.
            - "conversation": General question or statement not matching above. Put response text in "text".

            Uzbek command examples:
            - "keyingisi" or "keyingiga o'tish" → {"action": "swipe_next"}
            - "oldingisi" → {"action": "swipe_prev"}
            - "youtubeni och" → {"action": "open_app", "app": "youtube"}
            - "instagramni och" → {"action": "open_app", "app": "instagram"}
            - "manbu gapni yoz: salom" → {"action": "type_text", "text": "salom"}
            - "yoz: [text]" → {"action": "type_text", "text": "[text]"}
            - Any other speech when an input field is active → {"action": "type_text", "text": "[transcribed speech]"}

            Additionally, you can control the entire phone screen with these actions:
            - {"action": "agent_task", "task": "description of what to do"} — for complex multi-step screen tasks (e.g. "open Instagram, go to my profile, open first post")
            - {"action": "read_screen"} — read everything visible on the current screen
            - {"action": "read_chat", "reply": "message to send"} — read chat messages and optionally auto-reply. Leave "reply" empty to just read.
            - {"action": "click_at", "x": 540, "y": 1200} — click at specific screen coordinates

            When the user asks you to do something on their phone (navigate apps, send messages, read screen content, interact with UI), use agent_task for complex multi-step tasks.
            When the user asks to read what's on screen, use read_screen.
            When the user asks to read or reply to chat messages, use read_chat.

            When detecting wake words "dopix aktivlash" or sleep "dopix uxla", respond with:
            {"action": "conversation", "text": "OK"}

            Keep responses concise. For conversation, respond in the same language the user used.
        """.trimIndent()

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", GEMINI_MODEL)
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("TEXT"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", "Aoede")
                            })
                        })
                    })
                })
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemInstruction)
                        })
                    })
                })
            })
        }

        val sent = ws.send(setupJson.toString())
        if (sent) {
            isSetupSent.set(true)
            Log.d(TAG, "Setup message sent")

            // Transition to SLEEPING after setup (wait for first wake word)
            DopixState.setMode(DopixState.Mode.SLEEPING)
        } else {
            Log.e(TAG, "Failed to send setup message")
        }
    }

    private fun sendAudioChunk(pcmData: ByteArray) {
        val ws = webSocket ?: return
        val encoded = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val message = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("data", encoded)
                        put("mime_type", "audio/pcm;rate=16000")
                    })
                })
            })
        }

        try {
            ws.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Response handling
    // -------------------------------------------------------------------------

    private fun handleGeminiMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // Handle setup completion
            if (json.has("setupComplete")) {
                Log.d(TAG, "Gemini setup complete")
                return
            }

            // Handle server content (text responses)
            val serverContent = json.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            val textBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) textBuilder.append(text)
            }

            val fullText = textBuilder.toString().trim()
            if (fullText.isBlank()) return

            Log.d(TAG, "Gemini response: $fullText")
            DopixState.setAiResponse(fullText)

            // Check turn complete
            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (!turnComplete) return  // Wait for complete response

            // Check for wake/sleep words in response transcript context
            val wakeResult = wakeWordDetector.check(fullText)
            when (wakeResult) {
                is WakeWordDetector.WakeResult.Activate -> {
                    Log.d(TAG, "Wake word confirmed by Gemini transcript")
                    DopixState.setMode(DopixState.Mode.ACTIVE)
                    DopixState.onTranscriptReceived?.invoke(fullText)
                    updateNotification()
                    return
                }
                is WakeWordDetector.WakeResult.Deactivate -> {
                    Log.d(TAG, "Sleep word detected, going to sleep")
                    DopixState.setMode(DopixState.Mode.SLEEPING)
                    DopixState.onTranscriptReceived?.invoke(fullText)
                    updateNotification()
                    return
                }
                is WakeWordDetector.WakeResult.None -> { /* process as command */ }
            }

            // Update transcript display
            DopixState.setTranscript(fullText)
            DopixState.onTranscriptReceived?.invoke(fullText)

            // Only process commands when ACTIVE
            if (!DopixState.isActive()) return

            // Process the command
            serviceScope.launch(Dispatchers.Main) {
                val result = commandProcessor.processGeminiResponse(fullText)
                when (result) {
                    is CommandProcessor.CommandResult.Success ->
                        Log.d(TAG, "Command executed successfully")
                    is CommandProcessor.CommandResult.Conversation ->
                        Log.d(TAG, "Conversation response: ${result.text}")
                    is CommandProcessor.CommandResult.NoAccessibility ->
                        Log.w(TAG, "Accessibility service not available")
                    is CommandProcessor.CommandResult.Error ->
                        Log.e(TAG, "Command error: ${result.message}")
                }
                DopixState.onCommandReceived?.invoke(fullText)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini response: ${e.message}")
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
            .setContentTitle(getString(R.string.notif_gemini_title))
            .setContentText(getString(R.string.notif_gemini_text))
            .setSmallIcon(R.drawable.ic_dopix)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val text = when (DopixState.mode.value) {
            DopixState.Mode.ACTIVE -> "Active — listening for commands"
            DopixState.Mode.SLEEPING -> "Sleeping — say \"dopix aktivlash\" to wake"
            else -> getString(R.string.notif_gemini_text)
        }

        val notification = NotificationCompat.Builder(this, DopixApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.notif_gemini_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_dopix)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        nm.notify(DopixApplication.NOTIF_ID_GEMINI, notification)
    }
}
