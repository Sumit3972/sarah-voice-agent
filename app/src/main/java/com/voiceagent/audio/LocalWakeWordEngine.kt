package com.voiceagent.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.WakeSensitivity
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class LocalWakeMatch(
    val keyword: String,
    val latencyMs: Long
)

/**
 * 100% offline 16 kHz / 16-bit PCM wake-word engine powered by BC-ResNet-8 ONNX (`sarah_wakeword_fp32.onnx`).
 * Continuously buffers a 2.0-second rolling window and runs quantized/FP32 ONNX Runtime inference on-device.
 */
class LocalWakeWordEngine(context: Context) {
    companion object {
        private const val MODEL_ASSET_PATH = "wakeword/sarah_wakeword_fp32.onnx"
        private const val WINDOW_SAMPLES = LogMelExtractor.WINDOW_SAMPLES // 32,000 (2.0s @ 16kHz)
        private const val RECENT_RMS_SAMPLES = 12_800 // 0.8s @ 16kHz
        private const val INFER_INTERVAL_FRAMES = 4 // 4 * 32ms = 128ms stride
        private const val SPEECH_HANGOVER_MS = 1200L
        private const val COOLDOWN_MS = 1400L
        private const val MIN_SPEECH_RMS = 0.007f
        private const val TARGET_QUIET_WAKE_RMS = 0.035f
        private const val MAX_LOW_VOICE_GAIN = 3.5f
        private const val MIN_GAIN_RMS_FLOOR = 0.0012f
        private const val QUIET_RMS_CEILING = 0.020f

        private val INPUT_SHAPE = longArrayOf(
            1L,
            1L,
            LogMelExtractor.N_MELS.toLong(),
            LogMelExtractor.EXPECTED_FRAMES.toLong()
        )

        internal fun matchesKeywordText(raw: String): Boolean = raw.trim().isNotEmpty()

        internal fun softmaxWakeProb(nonWakeLogit: Float, wakeLogit: Float): Float {
            val m = max(nonWakeLogit, wakeLogit)
            val e0 = exp((nonWakeLogit - m).toDouble())
            val e1 = exp((wakeLogit - m).toDouble())
            return (e1 / (e0 + e1)).toFloat()
        }

        internal fun frameRms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) sum += (s * s).toDouble()
            return sqrt(sum / samples.size).toFloat()
        }

        internal fun computeAdaptiveGain(
            rms: Float,
            targetRms: Float = TARGET_QUIET_WAKE_RMS,
            maxGain: Float = MAX_LOW_VOICE_GAIN,
            minRmsThreshold: Float = MIN_GAIN_RMS_FLOOR
        ): Float {
            if (rms < minRmsThreshold || rms >= QUIET_RMS_CEILING) return 1.0f
            return (targetRms / rms).coerceIn(1.0f, maxGain)
        }

        internal fun applyGainClamped(samples: FloatArray, gain: Float): FloatArray {
            if (gain <= 1.02f) return samples
            val out = FloatArray(samples.size)
            for (i in samples.indices) {
                out[i] = (samples[i] * gain).coerceIn(-0.95f, 0.95f)
            }
            return out
        }
    }

    private val appContext = context.applicationContext
    private val engineLock = Any()
    private val melExtractor = LogMelExtractor()

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "mel_input"

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val ringBuf = FloatArray(WINDOW_SAMPLES)
    private val linearWindow = FloatArray(WINDOW_SAMPLES)
    private var ringWriteIdx = 0
    private var framesSinceInfer = 0
    private var hitStreak = 0
    private var lastSpeechActiveAtMs = 0L
    private var cooldownUntilMs = 0L
    private var currentSensitivity: WakeSensitivity = WakeSensitivity.BALANCED

    init {
        initEngine()
    }

    private fun initEngine() {
        synchronized(engineLock) {
            runCatching {
                val modelBytes = appContext.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                val createdSession = env.createSession(modelBytes, opts)
                val firstInput = createdSession.inputNames.firstOrNull() ?: "mel_input"

                ortEnv = env
                session = createdSession
                inputName = firstInput
                isAvailable = true
                Log.i(
                    PipelineConfig.LOG_TAG,
                    "[LocalWake] BC-ResNet-8 ONNX initialized (sensitivity=${currentSensitivity.name}, thr=${currentSensitivity.threshold}, hits=${currentSensitivity.consecutiveHits})"
                )
            }.onFailure { err ->
                isAvailable = false
                session = null
                Log.e(PipelineConfig.LOG_TAG, "[LocalWake] Failed to initialize BC-ResNet-8 ONNX: ${err.message}", err)
            }
        }
    }

    fun reconfigure(sensitivity: WakeSensitivity) {
        synchronized(engineLock) {
            currentSensitivity = sensitivity
            hitStreak = 0
            Log.i(
                PipelineConfig.LOG_TAG,
                "[LocalWake] Sensitivity updated -> ${sensitivity.name} (thr=${sensitivity.threshold}, hits=${sensitivity.consecutiveHits})"
            )
        }
    }

    /**
     * Pushes a 16-bit signed PCM frame into the 2.0-second rolling ring buffer and runs
     * BC-ResNet-8 ONNX inference every ~128 ms while speech energy is active.
     */
    fun processFrame(
        pcm16: ShortArray,
        sampleCount: Int,
        vadSpeech: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): LocalWakeMatch? {
        if (!isAvailable || sampleCount <= 0) return null

        synchronized(engineLock) {
            val env = ortEnv ?: return null
            val sess = session ?: return null

            // Write 16-bit PCM samples normalized to [-1, 1] into circular 2.0s buffer
            val count = sampleCount.coerceAtMost(pcm16.size)
            for (i in 0 until count) {
                ringBuf[ringWriteIdx] = pcm16[i] / 32768.0f
                ringWriteIdx = (ringWriteIdx + 1) % WINDOW_SAMPLES
            }

            if (nowMs < cooldownUntilMs) {
                return null
            }

            if (vadSpeech) {
                lastSpeechActiveAtMs = nowMs
            }

            val speechWindowActive = vadSpeech || (nowMs - lastSpeechActiveAtMs <= SPEECH_HANGOVER_MS)
            if (!speechWindowActive) {
                hitStreak = 0
                framesSinceInfer = 0
                return null
            }

            framesSinceInfer++
            if (framesSinceInfer < INFER_INTERVAL_FRAMES) {
                return null
            }
            framesSinceInfer = 0

            // Check recent 0.8s RMS energy before invoking ONNX
            var sumSq = 0.0
            for (i in 1..RECENT_RMS_SAMPLES) {
                val idx = (ringWriteIdx - i + WINDOW_SAMPLES) % WINDOW_SAMPLES
                val s = ringBuf[idx]
                sumSq += (s * s).toDouble()
            }
            val recentRms = sqrt(sumSq / RECENT_RMS_SAMPLES).toFloat()
            if (recentRms < MIN_SPEECH_RMS) {
                hitStreak = 0
                return null
            }

            // Linearize circular ring buffer oldest -> newest
            val tailLen = WINDOW_SAMPLES - ringWriteIdx
            ringBuf.copyInto(linearWindow, destinationOffset = 0, startIndex = ringWriteIdx, endIndex = WINDOW_SAMPLES)
            if (ringWriteIdx > 0) {
                ringBuf.copyInto(linearWindow, destinationOffset = tailLen, startIndex = 0, endIndex = ringWriteIdx)
            }

            val t0 = System.currentTimeMillis()
            val mel = melExtractor.computeLogMel(linearWindow)
            val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), INPUT_SHAPE).use { tensor ->
                sess.run(mapOf(inputName to tensor)).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (results[0].value as Array<FloatArray>)[0]
                    softmaxWakeProb(logits[0], logits[1])
                }
            }
            val elapsed = System.currentTimeMillis() - t0

            if (prob >= currentSensitivity.threshold) {
                hitStreak++
            } else {
                hitStreak = 0
            }

            if (hitStreak >= currentSensitivity.consecutiveHits) {
                ringBuf.fill(0f)
                ringWriteIdx = 0
                hitStreak = 0
                framesSinceInfer = 0
                cooldownUntilMs = nowMs + COOLDOWN_MS
                Log.i(
                    PipelineConfig.LOG_TAG,
                    "[LocalWake] Hit 'sarah' (prob=${"%.3f".format(prob)}, rms=${"%.4f".format(recentRms)}, ${elapsed}ms)"
                )
                return LocalWakeMatch(keyword = "sarah", latencyMs = elapsed)
            }
        }
        return null
    }

    fun resetStream() {
        synchronized(engineLock) {
            ringBuf.fill(0f)
            ringWriteIdx = 0
            hitStreak = 0
            framesSinceInfer = 0
        }
    }

    fun release() {
        synchronized(engineLock) {
            isAvailable = false
            runCatching { session?.close() }
            session = null
            ortEnv = null
        }
    }
}
