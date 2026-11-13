package com.fy.facetranslate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Captures 16 kHz mono 16-bit PCM (the Live API input format) in ~100 ms chunks.
 * Chunks are delivered on a background thread.
 */
class MicRecorder(private val onChunk: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Returns false if the microphone could not be opened (permission revoked, busy). */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO before starting
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4),
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val effects = listOfNotNull(
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null,
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null,
        )
        running = true
        record.startRecording()
        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0 && running) onChunk(buf.copyOf(n))
                }
            } finally {
                runCatching { record.stop() }
                effects.forEach { it.release() }
                record.release()
            }
        }, "mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10 // 100 ms
    }
}
