package com.fy.facetranslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams 24 kHz mono 16-bit PCM (the Live API output format) to the loudspeaker.
 * Audio is played as soon as it arrives, so the first syllable is heard before
 * the whole translation has been generated.
 */
class PcmPlayer {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "player") }
    private val generation = AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null
    private var framesWritten = 0L

    /** Starts a new playback stream, cancelling any previous one. */
    fun begin(): Int {
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) return@execute
            releaseTrack()
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE)) // >= 0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
            framesWritten = 0
        }
        return gen
    }

    fun write(gen: Int, pcm: ByteArray) {
        executor.execute {
            val t = track ?: return@execute
            if (gen != generation.get()) return@execute
            var off = 0
            // Small slices so that stop() (pause + flush) unblocks a pending write within one slice.
            while (off < pcm.size && gen == generation.get()) {
                val n = t.write(pcm, off, minOf(SLICE_BYTES, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            framesWritten += off / 2
        }
    }

    /**
     * Signals that no more audio will arrive for [gen]. [onDone] runs on the player thread
     * once the last frame has been played, with false if nothing was played or it was cancelled.
     */
    fun finish(gen: Int, onDone: (played: Boolean) -> Unit) {
        executor.execute {
            val t = track
            if (gen != generation.get() || t == null || framesWritten == 0L) {
                onDone(false)
                return@execute
            }
            val deadline = System.currentTimeMillis() + framesWritten * 1000 / SAMPLE_RATE + 2_000
            while (gen == generation.get() && t.playbackHeadPosition < framesWritten &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            if (gen == generation.get()) {
                releaseTrack()
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    /** Stops playback immediately. Pending finish callbacks report false. */
    fun stop() {
        generation.incrementAndGet()
        val t = track
        runCatching { t?.pause(); t?.flush() }
        executor.execute { releaseTrack() }
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val SLICE_BYTES = SAMPLE_RATE * 2 / 20 // 50 ms
    }
}
