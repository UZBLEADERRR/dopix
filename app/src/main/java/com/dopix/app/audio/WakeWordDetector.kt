package com.dopix.app.audio

import android.util.Log

/**
 * Detects wake words from transcribed text.
 *
 * Wake word: "dopix aktivlash" → activate
 * Sleep word: "dopix uxla"     → deactivate
 *
 * Also provides lightweight energy-based VAD to decide whether
 * to send audio to Gemini in SLEEPING mode.
 */
class WakeWordDetector {

    private val TAG = "WakeWordDetector"

    companion object {
        // Primary wake/sleep phrases
        const val WAKE_PHRASE = "dopix aktivlash"
        const val SLEEP_PHRASE = "dopix uxla"

        // Alternate / partial matches (Uzbek transliteration variants)
        private val WAKE_ALTERNATIVES = listOf(
            "dopix aktivlash",
            "dopiks aktivlash",
            "dopik aktivlash",
            "dopix activate",
            "dopix on"
        )
        private val SLEEP_ALTERNATIVES = listOf(
            "dopix uxla",
            "dopiks uxla",
            "dopix sleep",
            "dopix off",
            "dopix stop"
        )

        // Energy threshold for rough voice activity detection (RMS of int16 samples)
        const val DEFAULT_ENERGY_THRESHOLD = 800.0
    }

    sealed class WakeResult {
        object None : WakeResult()
        object Activate : WakeResult()
        object Deactivate : WakeResult()
    }

    /**
     * Check a transcription string for wake / sleep words.
     */
    fun check(transcript: String): WakeResult {
        val lower = transcript.lowercase().trim()

        for (phrase in WAKE_ALTERNATIVES) {
            if (lower.contains(phrase)) {
                Log.d(TAG, "Wake word detected: $phrase in [$transcript]")
                return WakeResult.Activate
            }
        }

        for (phrase in SLEEP_ALTERNATIVES) {
            if (lower.contains(phrase)) {
                Log.d(TAG, "Sleep word detected: $phrase in [$transcript]")
                return WakeResult.Deactivate
            }
        }

        return WakeResult.None
    }

    /**
     * Compute RMS energy of a 16-bit PCM audio buffer.
     * Returns true if energy exceeds threshold (voice likely present).
     */
    fun hasVoiceActivity(pcmBuffer: ByteArray, threshold: Double = DEFAULT_ENERGY_THRESHOLD): Boolean {
        if (pcmBuffer.isEmpty()) return false

        var sumSquares = 0.0
        val sampleCount = pcmBuffer.size / 2  // 16-bit = 2 bytes per sample

        for (i in 0 until sampleCount) {
            val low = pcmBuffer[i * 2].toInt() and 0xFF
            val high = pcmBuffer[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        val rms = if (sampleCount > 0) Math.sqrt(sumSquares / sampleCount) else 0.0
        return rms > threshold
    }

    /**
     * Compute raw RMS value (useful for calibration / display).
     */
    fun computeRms(pcmBuffer: ByteArray): Double {
        if (pcmBuffer.isEmpty()) return 0.0

        var sumSquares = 0.0
        val sampleCount = pcmBuffer.size / 2

        for (i in 0 until sampleCount) {
            val low = pcmBuffer[i * 2].toInt() and 0xFF
            val high = pcmBuffer[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        return if (sampleCount > 0) Math.sqrt(sumSquares / sampleCount) else 0.0
    }
}
