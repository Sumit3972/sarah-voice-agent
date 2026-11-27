package com.voiceagent.audio

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.voiceagent.config.PipelineConfig
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioFrameAnalysis(
    val pcm16Bytes: ByteArray,
    val samples: ShortArray,
    val rmsDbfs: Double,
    val snrDb: Double,
    val noiseFloorDbfs: Double,
    val vadSpeech: Boolean
)

/**
 * High-pass 85Hz Biquad filter (removes HVAC rumble/mic handling thumps) +
 * Adaptive ambient noise floor tracker + On-device Silero VAD DNN.
 */
class AudioPipeline(context: Context) {
    private val vad = VadSilero(
        context = context.applicationContext,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        silenceDurationMs = 200,
        speechDurationMs = 32
    )

    // 2nd-order Butterworth 85Hz High-Pass Filter coefficients at 16kHz
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private var reusableFiltered = ShortArray(PipelineConfig.FRAME_SAMPLES)
    private var reusableBytes = ByteArray(PipelineConfig.FRAME_SAMPLES * 2)

    @Volatile
    var noiseFloorDbfs: Double = PipelineConfig.INITIAL_NOISE_FLOOR_DBFS
        private set

    init {
        val f0 = 85.0
        val w0 = 2.0 * Math.PI * f0 / PipelineConfig.SAMPLE_RATE_HZ
        val alpha = sin(w0) / (2.0 * 0.70710678)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = (-(1.0 + cosW0)) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun processFrame(rawSamples: ShortArray, count: Int = rawSamples.size): AudioFrameAnalysis {
        if (reusableFiltered.size != count) {
            reusableFiltered = ShortArray(count)
            reusableBytes = ByteArray(count * 2)
        }
        val filtered = reusableFiltered
        val bytes = reusableBytes
        var sumSq = 0.0

        for (i in 0 until count) {
            val x0 = rawSamples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0

            val clamped = y0.toInt().coerceIn(-32767, 32767).toShort()
            filtered[i] = clamped
            val s = clamped.toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            sumSq += s.toDouble() * s.toDouble()
        }

        val rmsNorm = sqrt(sumSq / count.coerceAtLeast(1)) / 32768.0
        val rmsDbfs = if (rmsNorm > 1e-6) 20.0 * log10(rmsNorm) else -96.0
        val vadSpeech = vad.isSpeech(bytes)

        // Update noise floor slowly ONLY when VAD is silent so user speech never raises the floor
        if (!vadSpeech) {
            noiseFloorDbfs = PipelineConfig.NOISE_FLOOR_EMA_ALPHA * noiseFloorDbfs +
                (1.0 - PipelineConfig.NOISE_FLOOR_EMA_ALPHA) * rmsDbfs
        }
        val snrDb = rmsDbfs - noiseFloorDbfs

        return AudioFrameAnalysis(
            pcm16Bytes = bytes,
            samples = filtered,
            rmsDbfs = rmsDbfs,
            snrDb = snrDb,
            noiseFloorDbfs = noiseFloorDbfs,
            vadSpeech = vadSpeech
        )
    }

    fun recalibrateFloor(targetDbfs: Double) {
        noiseFloorDbfs = (0.7 * noiseFloorDbfs) + (0.3 * targetDbfs)
    }

    fun close() {
        runCatching { vad.close() }
    }
}
