package com.voiceagent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.voiceagent.config.PipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class LowLatencyAudioPlayer(
    private val focusCoordinator: CallAndAudioFocusCoordinator? = null
) {
    companion object {
        private const val TAG = "VoiceAgentPlayer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private val acceptingChunks = AtomicBoolean(false)
    private val isActivelyPlaying = AtomicBoolean(false)

    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var lastAudioWriteMs: Long = 0L
    private var currentSampleRate = 0
    private var writerJob: Job? = null

    fun isPlaying(): Boolean = isActivelyPlaying.get()

    fun isActivelyOutputtingAudio(): Boolean {
        val now = System.currentTimeMillis()
        val tailActive = lastAudioWriteMs > 0L && (now - lastAudioWriteMs) < 350L
        return isActivelyPlaying.get() || acceptingChunks.get() || tailActive
    }

    @Synchronized
    fun prepareAndPlay(sampleRate: Int = PipelineConfig.FISH_TTS_SAMPLE_RATE) {
        abortImmediate()
        focusCoordinator?.requestAssistantDuckFocus()
        ensureTrack(sampleRate)
        pcmQueue.clear()
        lastAudioWriteMs = 0L
        acceptingChunks.set(true)
        isActivelyPlaying.set(true)

        val track = audioTrack ?: return
        runCatching { track.play() }

        writerJob = scope.launch {
            while (isActivelyPlaying.get()) {
                val chunk = pcmQueue.poll()
                if (chunk != null) {
                    val written = runCatching {
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    }.getOrDefault(-1)
                    if (written > 0) {
                        lastAudioWriteMs = System.currentTimeMillis()
                    }
                    if (written < 0) break
                } else {
                    if (!acceptingChunks.get() && pcmQueue.isEmpty()) {
                        // Hardware AudioTrack buffer drains fast under PERFORMANCE_MODE_LOW_LATENCY
                        delay(180)
                        break
                    }
                    delay(10)
                }
            }
            if (lastAudioWriteMs > 0L) {
                lastAudioWriteMs = System.currentTimeMillis()
            }
            isActivelyPlaying.set(false)
        }
    }

    fun enqueuePcm(pcm16Bytes: ByteArray) {
        if (!acceptingChunks.get() || pcm16Bytes.isEmpty()) return
        pcmQueue.offer(pcm16Bytes)
    }

    suspend fun drainAndStop() {
        acceptingChunks.set(false)
        writerJob?.join()
        isActivelyPlaying.set(false)
        focusCoordinator?.abandonAssistantFocus()
        runCatching {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
            }
        }
    }

    @Synchronized
    fun abortImmediate() {
        val t0 = System.currentTimeMillis()
        acceptingChunks.set(false)
        isActivelyPlaying.set(false)
        lastAudioWriteMs = 0L
        pcmQueue.clear()
        writerJob?.cancel()
        writerJob = null
        focusCoordinator?.abandonAssistantFocus()
        runCatching {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                }
                it.flush()
            }
        }
        Log.d(TAG, "AudioTrack abortImmediate in ${System.currentTimeMillis() - t0}ms")
    }

    private fun ensureTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return
        }
        releaseTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        currentSampleRate = sampleRate
    }

    fun release() {
        abortImmediate()
        releaseTrack()
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.release() }
        audioTrack = null
        currentSampleRate = 0
    }
}
