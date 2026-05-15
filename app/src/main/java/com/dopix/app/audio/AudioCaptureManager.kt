package com.dopix.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Manages continuous audio capture from the microphone.
 *
 * Captures 16kHz, 16-bit mono PCM.
 * Delivers raw PCM chunks via callback.
 * Supports pause/resume to conserve resources when sleeping.
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_BYTES = 4096

        // Frames of audio to collect per callback (~128ms @ 16kHz, 16-bit)
        private const val READ_SIZE = BUFFER_SIZE_BYTES
    }

    interface AudioCallback {
        fun onAudioChunk(pcmData: ByteArray, rms: Double)
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isCapturing = false

    @Volatile
    private var isPaused = false

    private var callback: AudioCallback? = null
    private val wakeWordDetector = WakeWordDetector()

    fun setCallback(cb: AudioCallback) {
        callback = cb
    }

    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        if (isCapturing) {
            Log.w(TAG, "Already capturing audio")
            return true
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf * 2, BUFFER_SIZE_BYTES * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}")
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        isCapturing = true
        isPaused = false

        captureJob = scope.launch {
            val buffer = ByteArray(READ_SIZE)
            Log.d(TAG, "Audio capture started")

            while (isActive && isCapturing) {
                if (isPaused) {
                    // Still drain audio to avoid overflow but don't callback
                    audioRecord?.read(buffer, 0, buffer.size)
                    continue
                }

                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    val rms = wakeWordDetector.computeRms(chunk)
                    callback?.onAudioChunk(chunk, rms)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                    break
                }
            }

            Log.d(TAG, "Audio capture loop ended")
        }

        return true
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "Audio capture paused")
    }

    fun resume() {
        isPaused = false
        Log.d(TAG, "Audio capture resumed")
    }

    fun stop() {
        isCapturing = false
        isPaused = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }

    fun isRunning(): Boolean = isCapturing && !isPaused

    fun isPaused(): Boolean = isPaused
}
