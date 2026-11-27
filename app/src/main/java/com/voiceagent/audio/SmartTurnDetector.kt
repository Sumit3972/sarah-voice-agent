package com.voiceagent.audio

import com.voiceagent.config.PipelineConfig
import kotlin.math.sqrt

data class EndOfTurnEvaluation(
    val completionProbability: Double,
    val recommendedWaitMs: Long,
    val reason: String
)

/**
 * Evaluates trailing acoustic prosody + live partial transcript syntax
 * to prevent cutting off the user during mid-sentence thinking pauses.
 */
class SmartTurnDetector {
    private val danglingTokens = setOf(
        "and", "or", "but", "so", "because", "since", "if", "when", "while", "however",
        "to", "for", "with", "at", "in", "on", "from", "into", "of", "the", "a", "an",
        "uh", "uhh", "um", "umm", "hmm", "er", "erm", "like", "actually",
        "basically", "literally", "mean", "wait", "matlab", "aur", "toh", "ki", "ke", "liye"
    )

    fun evaluatePause(
        recentSamples: ShortArray,
        partialTranscript: String
    ): EndOfTurnEvaluation {
        val cleaned = partialTranscript.trim()
        val lower = cleaned.lowercase().replace(Regex("[^a-z0-9\\s\\u0900-\\u097F']"), " ").trim()
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lastWord = words.lastOrNull().orEmpty()

        // 0. Fast commit for standalone stop / cancel commands after wake barge-in
        val normStop = words.joinToString(" ")
        if (normStop in setOf(
                "stop", "stop it", "stop talking", "stop speaking", "please stop",
                "quiet", "be quiet", "shut up", "cancel", "pause", "enough",
                "never mind", "nevermind", "forget it", "bas", "ruko", "chup", "band karo"
            )
        ) {
            return EndOfTurnEvaluation(
                completionProbability = 0.98,
                recommendedWaitMs = 180L,
                reason = "explicit_stop_command"
            )
        }

        // 1. Check syntactic incompleteness on streaming Deepgram partial transcript
        val endsWithCommaOrEllipsis = cleaned.endsWith(",") || cleaned.endsWith("...") || cleaned.endsWith("-")
        val isSingleWakeOrFiller = words.size <= 1 && lastWord in danglingTokens
        val hasDanglingTail = lastWord in danglingTokens || endsWithCommaOrEllipsis || isSingleWakeOrFiller

        if (hasDanglingTail) {
            return EndOfTurnEvaluation(
                completionProbability = 0.25,
                recommendedWaitMs = PipelineConfig.T_WAIT_HESITATION_MS,
                reason = "dangling_tail:'$lastWord'"
            )
        }

        // 2. Acoustic prosody check on trailing ~800ms of speech before silence
        val acousticDropScore = estimateTrailingEnergyDecay(recentSamples)

        // Complete punctuation or clear multi-word query with downward cadence
        val endsWithTerminalPunct = cleaned.endsWith(".") || cleaned.endsWith("?") || cleaned.endsWith("!") || cleaned.endsWith("।")
        if (endsWithTerminalPunct && words.size >= 2) {
            return EndOfTurnEvaluation(
                completionProbability = 0.90,
                recommendedWaitMs = PipelineConfig.T_WAIT_HIGH_CONF_MS,
                reason = "terminal_punct+decay=${"%.2f".format(acousticDropScore)}"
            )
        }

        if (words.size >= 3 && acousticDropScore >= 0.60) {
            return EndOfTurnEvaluation(
                completionProbability = 0.82,
                recommendedWaitMs = PipelineConfig.T_WAIT_HIGH_CONF_MS,
                reason = "acoustic_decay=${"%.2f".format(acousticDropScore)}"
            )
        }

        return EndOfTurnEvaluation(
            completionProbability = 0.65,
            recommendedWaitMs = PipelineConfig.T_WAIT_MID_CONF_MS,
            reason = "neutral_pause(words=${words.size})"
        )
    }

    private fun estimateTrailingEnergyDecay(samples: ShortArray): Double {
        if (samples.size < 3200) return 0.5
        val half = samples.size / 2
        val firstRms = rms(samples, 0, half)
        val secondRms = rms(samples, half, samples.size)
        if (firstRms <= 1.0) return 0.5
        val ratio = secondRms / firstRms
        return when {
            ratio < 0.55 -> 0.85 // Clear falling tail energy
            ratio < 0.80 -> 0.65
            else -> 0.40 // Sustained/flat energy -> likely mid-thought pause
        }
    }

    private fun rms(samples: ShortArray, start: Int, end: Int): Double {
        var sum = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (end - start).coerceAtLeast(1))
    }
}
