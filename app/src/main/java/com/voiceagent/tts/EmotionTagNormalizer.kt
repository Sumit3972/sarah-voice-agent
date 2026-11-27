package com.voiceagent.tts

/**
 * Normalizes LLM text into clean spoken prose while preserving official Fish Audio S2 / S2.1 Pro
 * square-bracket inline & mid-sentence emotion/paralinguistic tags such as [laughing], [sigh],
 * [happy], [excited], [whispering], [warmly], [soft tone], [chuckle], [curious], etc.
 */
object EmotionTagNormalizer {
    private val EMOJI_REGEX = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")
    private val MARKDOWN_REGEX = Regex("[*_`#]+")
    private val SQUARE_TAG_REGEX = Regex("\\[(.*?)\\]")
    private val PAREN_EMOTION_REGEX = Regex(
        "\\(([a-zA-Z][a-zA-Z\\s-]{1,28})\\)"
    )

    private val KNOWN_VOCAL_KEYWORDS = setOf(
        "happy", "excited", "calm", "soft", "warm", "warmly", "whisper", "whispering", "whispers",
        "laugh", "laughing", "laughs", "chuckle", "chuckles", "giggle", "giggles", "sigh", "sighs",
        "gasp", "gasps", "curious", "cheerful", "empathetic", "playful", "playfully", "serious",
        "sad", "surprised", "thinking", "gentle", "gently", "voice breaking", "breathy", "nervous",
        "nervously", "proud", "proudly", "amused", "bright", "tired", "relieved", "soothing",
        "pause", "short pause", "long pause", "clear throat"
    )

    fun sanitizeForSpeech(
        text: String,
        allowLeadingEmotionTag: Boolean = true,
        allowInlineEmotionTags: Boolean = true
    ): String {
        if (text.isBlank()) return ""
        var cleaned = EMOJI_REGEX.replace(text, "")
        cleaned = MARKDOWN_REGEX.replace(cleaned, "")

        // Upgrade legacy Fish S1 parenthetical emotion cues to S2.1 Pro [square brackets]
        cleaned = PAREN_EMOTION_REGEX.replace(cleaned) { mr ->
            val inner = mr.groupValues[1].trim().lowercase()
            if (isLikelyVocalTag(inner)) "[$inner]" else mr.value
        }

        val result = StringBuilder()
        var lastIndex = 0
        var isFirstToken = true

        for (match in SQUARE_TAG_REGEX.findAll(cleaned)) {
            val before = cleaned.substring(lastIndex, match.range.first)
            if (before.isNotBlank()) {
                isFirstToken = false
            }
            result.append(before)
            lastIndex = match.range.last + 1

            val normalizedTag = normalizeBracketTag(match.groupValues[1])
            val isLeading = result.toString().trim().isEmpty() && isFirstToken
            val permitted = normalizedTag != null && (
                (isLeading && allowLeadingEmotionTag) ||
                    (!isLeading && allowInlineEmotionTags)
                )
            if (permitted) {
                if (result.isNotEmpty() && !result.last().isWhitespace()) {
                    result.append(' ')
                }
                result.append(normalizedTag)
                result.append(' ')
            }
        }
        if (lastIndex < cleaned.length) {
            result.append(cleaned.substring(lastIndex))
        }

        return result.toString()
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun hasSpokenWordsOutsideTags(text: String): Boolean {
        val stripped = SQUARE_TAG_REGEX.replace(text, "").trim()
        return stripped.any { it.isLetterOrDigit() }
    }

    /**
     * Removes Fish Audio delivery tags (e.g. [warmly], [happy], [laughing]) and any leaked
     * <untrusted_*> XML wrapper tags for clean on-screen chat display.
     */
    fun stripTagsForDisplay(text: String): String {
        if (text.isBlank()) return ""
        val withoutXml = text.replace(Regex("</?untrusted_[^>]*>", RegexOption.IGNORE_CASE), "")
        val withoutBrackets = SQUARE_TAG_REGEX.replace(withoutXml) { mr ->
            if (normalizeBracketTag(mr.groupValues[1]) != null) " " else mr.value
        }
        return withoutBrackets
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isLikelyVocalTag(rawLower: String): Boolean {
        if (rawLower.isBlank() || rawLower.length > 36) return false
        if (rawLower.startsWith("escalate") || rawLower.startsWith("http") || rawLower.startsWith("tool")) {
            return false
        }
        return KNOWN_VOCAL_KEYWORDS.any { kw ->
            rawLower == kw || rawLower.contains(kw)
        }
    }

    private fun normalizeBracketTag(rawContent: String): String? {
        val raw = rawContent.trim().lowercase().replace(Regex("\\s+"), " ")
        if (raw.isBlank() || raw.length > 40) return null
        if (raw.all { it.isDigit() }) return null // never speak UI ref markers [1]
        if (raw.contains("escalate_gemini") || raw.contains("untrusted_") || raw.startsWith("http")) return null

        return when {
            raw == "slight laugh" || raw == "chuckle" || raw == "chuckles" -> "[chuckle]"
            raw == "laughs" || raw == "laughing" -> "[laughing]"
            raw == "sighs" || raw == "sigh" -> "[sigh]"
            raw == "whisper" || raw == "whispering" || raw == "whispers" -> "[whispering]"
            isLikelyVocalTag(raw) -> "[$raw]"
            // Allow concise 1-3 word alphabetical descriptive S2.1 Pro vocal instructions
            raw.split(' ').size <= 3 && raw.all { it.isLetter() || it == ' ' || it == '-' } -> "[$raw]"
            else -> null
        }
    }
}
