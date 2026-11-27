package com.voiceagent.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin 40-bin log-Mel spectrogram extractor calibrated bit-for-bit against
 * `train_sarah_bcresnet.py` (`compute_log_mel`):
 *  - 16 kHz audio, 2.0 s window (`32,000` samples)
 *  - RMS peak stabilization (`rms > 0.002f`)
 *  - Reflect padding (`256` each side)
 *  - 400-sample Hann window centered in 512-point FFT, hop `160` -> `201` frames
 *  - Slaney-normalized 40-bin Mel filterbank (`20 Hz` - `7600 Hz`)
 *  - Per-utterance CMVN (`(logMel - mean) / (std + 1e-5f)`), row-major `[mel * 201 + frame]`
 */
class LogMelExtractor {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = 32_000
        const val N_FFT = 512
        const val WIN_LENGTH = 400
        const val HOP_LENGTH = 160
        const val N_MELS = 40
        const val EXPECTED_FRAMES = 201
        const val FMIN = 20.0f
        const val FMAX = 7600.0f

        private const val N_FREQS = N_FFT / 2 + 1 // 257
        private const val REFLECT_PAD = N_FFT / 2 // 256
        private const val SIG_LEN = WINDOW_SAMPLES + REFLECT_PAD * 2 // 32,512
        private const val OUTPUT_LEN = N_MELS * EXPECTED_FRAMES // 8,040
        private const val LOG2_NFFT = 9 // 2^9 = 512

        private fun hzToMel(hz: Float): Double = 2595.0 * log10(1.0 + hz / 700.0)
        private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
    }

    private val winPad = FloatArray(N_FFT)
    private val melFb = Array(N_MELS) { FloatArray(N_FREQS) }
    private val bitRev = IntArray(N_FFT)
    private val cosTable = DoubleArray(N_FFT / 2)
    private val sinTable = DoubleArray(N_FFT / 2)

    // Reusable single-threaded scratch buffers (accessed under LocalWakeWordEngine lock or per instance)
    private val workAudio = FloatArray(WINDOW_SAMPLES)
    private val paddedSig = FloatArray(SIG_LEN)
    private val fftRe = DoubleArray(N_FFT)
    private val fftIm = DoubleArray(N_FFT)
    private val power = FloatArray(N_FREQS)
    private val outMel = FloatArray(OUTPUT_LEN)

    init {
        initWindow()
        initMelFilterbank()
        initFftTables()
    }

    private fun initWindow() {
        val offset = (N_FFT - WIN_LENGTH) / 2 // 56
        val denom = (WIN_LENGTH - 1).toDouble()
        for (i in 0 until WIN_LENGTH) {
            winPad[offset + i] = (0.5 * (1.0 - cos(2.0 * PI * i / denom))).toFloat()
        }
    }

    private fun initMelFilterbank() {
        val fftFreqs = DoubleArray(N_FREQS) { k -> (SAMPLE_RATE / 2.0) * k / (N_FREQS - 1) }
        val melLow = hzToMel(FMIN)
        val melHigh = hzToMel(FMAX)
        val hzPts = DoubleArray(N_MELS + 2) { idx ->
            val m = melLow + (melHigh - melLow) * idx / (N_MELS + 1)
            melToHz(m)
        }
        for (m in 0 until N_MELS) {
            val fLeft = hzPts[m]
            val fCenter = hzPts[m + 1]
            val fRight = hzPts[m + 2]
            val leftDen = max(1e-6, fCenter - fLeft)
            val rightDen = max(1e-6, fRight - fCenter)
            val enorm = 2.0 / max(1e-6, fRight - fLeft)
            val row = melFb[m]
            for (k in 0 until N_FREQS) {
                val f = fftFreqs[k]
                val up = (f - fLeft) / leftDen
                val down = (fRight - f) / rightDen
                row[k] = (max(0.0, min(up, down)) * enorm).toFloat()
            }
        }
    }

    private fun initFftTables() {
        for (i in 0 until N_FFT) {
            var rev = 0
            var x = i
            for (b in 0 until LOG2_NFFT) {
                rev = (rev shl 1) or (x and 1)
                x = x ushr 1
            }
            bitRev[i] = rev
        }
        for (k in 0 until N_FFT / 2) {
            val angle = -2.0 * PI * k / N_FFT
            cosTable[k] = cos(angle)
            sinTable[k] = sin(angle)
        }
    }

    private fun fft512InPlace() {
        for (i in 0 until N_FFT) {
            val j = bitRev[i]
            if (i < j) {
                val tr = fftRe[i]
                val ti = fftIm[i]
                fftRe[i] = fftRe[j]
                fftIm[i] = fftIm[j]
                fftRe[j] = tr
                fftIm[j] = ti
            }
        }
        var halfSize = 1
        while (halfSize < N_FFT) {
            val step = halfSize shl 1
            val tableStep = N_FFT / step
            var base = 0
            while (base < N_FFT) {
                var twIdx = 0
                for (k in 0 until halfSize) {
                    val i0 = base + k
                    val i1 = i0 + halfSize
                    val wr = cosTable[twIdx]
                    val wi = sinTable[twIdx]
                    val tr = wr * fftRe[i1] - wi * fftIm[i1]
                    val ti = wr * fftIm[i1] + wi * fftRe[i1]
                    fftRe[i1] = fftRe[i0] - tr
                    fftIm[i1] = fftIm[i0] - ti
                    fftRe[i0] = fftRe[i0] + tr
                    fftIm[i0] = fftIm[i0] + ti
                    twIdx += tableStep
                }
                base += step
            }
            halfSize = step
        }
    }

    /**
     * Compute normalized `[1, 1, 40, 201]` flattened log-Mel spectrogram from 16 kHz float32 PCM.
     */
    fun computeLogMel(audio: FloatArray): FloatArray {
        workAudio.fill(0f)
        if (audio.size <= WINDOW_SAMPLES) {
            audio.copyInto(workAudio, destinationOffset = 0, startIndex = 0, endIndex = audio.size)
        } else {
            val start = audio.size - WINDOW_SAMPLES
            audio.copyInto(workAudio, destinationOffset = 0, startIndex = start, endIndex = audio.size)
        }

        // Peak-stabilize slightly while preserving relative silence vs speech
        var sumSq = 0.0
        for (i in 0 until WINDOW_SAMPLES) {
            val s = workAudio[i]
            sumSq += (s * s).toDouble()
        }
        val rms = sqrt(sumSq / WINDOW_SAMPLES).toFloat() + 1e-8f
        if (rms > 0.002f) {
            val denom = max(0.05f, rms * 3.0f)
            for (i in 0 until WINDOW_SAMPLES) {
                workAudio[i] = (workAudio[i] / denom).coerceIn(-1.0f, 1.0f)
            }
        }

        // Reflect pad (np.pad(..., (256, 256), mode="reflect"))
        for (i in 0 until REFLECT_PAD) {
            paddedSig[REFLECT_PAD - 1 - i] = workAudio[i + 1]
            paddedSig[REFLECT_PAD + WINDOW_SAMPLES + i] = workAudio[WINDOW_SAMPLES - 2 - i]
        }
        workAudio.copyInto(paddedSig, destinationOffset = REFLECT_PAD)

        // Frame STFT + Mel projection + ln(mel + 1e-6)
        var sumLog = 0.0
        for (t in 0 until EXPECTED_FRAMES) {
            val frameStart = t * HOP_LENGTH
            for (i in 0 until N_FFT) {
                fftRe[i] = (paddedSig[frameStart + i] * winPad[i]).toDouble()
                fftIm[i] = 0.0
            }
            fft512InPlace()

            for (k in 0 until N_FREQS) {
                val re = fftRe[k].toFloat()
                val im = fftIm[k].toFloat()
                power[k] = re * re + im * im
            }

            for (m in 0 until N_MELS) {
                val fbRow = melFb[m]
                var e = 0.0f
                for (k in 0 until N_FREQS) {
                    e += fbRow[k] * power[k]
                }
                val lv = ln(e + 1e-6f)
                outMel[m * EXPECTED_FRAMES + t] = lv
                sumLog += lv.toDouble()
            }
        }

        // Per-utterance CMVN across all [40 x 201] entries
        val mean = (sumLog / OUTPUT_LEN).toFloat()
        var varSum = 0.0
        for (i in 0 until OUTPUT_LEN) {
            val d = (outMel[i] - mean).toDouble()
            varSum += d * d
        }
        val std = sqrt(varSum / OUTPUT_LEN).toFloat() + 1e-5f
        val invStd = 1.0f / std
        for (i in 0 until OUTPUT_LEN) {
            outMel[i] = (outMel[i] - mean) * invStd
        }

        return outMel.copyOf()
    }
}
