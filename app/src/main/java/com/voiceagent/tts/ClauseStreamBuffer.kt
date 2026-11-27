package com.voiceagent.tts

class ClauseStreamBuffer {
    private val buffer = StringBuilder()
    private var sentFirstClause = false

    fun appendDelta(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val ready = mutableListOf<String>()

        while (true) {
            val text = buffer.toString()
            val boundary = findClauseBoundary(text, isFirst = !sentFirstClause)
            if (boundary <= 0) break

            val rawClause = text.substring(0, boundary).trim()
            val cleaned = EmotionTagNormalizer.sanitizeForSpeech(
                rawClause,
                allowLeadingEmotionTag = true,
                allowInlineEmotionTags = true
            )
            // If a cut only produced a bare [emotion] tag without spoken words yet, keep waiting for words
            if (cleaned.isNotEmpty() && !EmotionTagNormalizer.hasSpokenWordsOutsideTags(cleaned)) {
                break
            }

            buffer.delete(0, boundary)
            if (cleaned.isNotEmpty()) {
                ready += cleaned
                sentFirstClause = true
            }
        }
        return ready
    }

    fun drainRemainder(): String? {
        val remaining = buffer.toString().trim()
        buffer.clear()
        if (remaining.isEmpty()) return null
        val cleaned = EmotionTagNormalizer.sanitizeForSpeech(
            remaining,
            allowLeadingEmotionTag = true,
            allowInlineEmotionTags = true
        )
        sentFirstClause = true
        return cleaned.takeIf { it.isNotEmpty() && EmotionTagNormalizer.hasSpokenWordsOutsideTags(it) }
    }

    fun reset() {
        buffer.clear()
        sentFirstClause = false
    }

    private fun findClauseBoundary(text: String, isFirst: Boolean): Int {
        val minLen = if (isFirst) 18 else 28
        var inBracket = false
        var bracketStart = -1

        for (i in text.indices) {
            val c = text[i]
            if (c == '[') {
                inBracket = true
                bracketStart = i
                continue
            }
            if (c == ']' && inBracket) {
                inBracket = false
                continue
            }
            // Do not slice through an unfinished [emotion] tag
            if (inBracket && (i - bracketStart) < 42) {
                continue
            }
            inBracket = false

            val isSentenceEnd = c == '.' || c == '!' || c == '?' || c == '\n' || c == '।'
            val isClausePause = (c == ',' || c == ';' || c == ':') && i >= minLen
            if ((isSentenceEnd && i >= 8) || isClausePause) {
                return i + 1
            }
        }
        if (text.length >= 130) {
            val lastSpace = text.lastIndexOf(' ', 129)
            if (lastSpace > 24 && !isInsideBracket(text, lastSpace)) return lastSpace + 1
        }
        return -1
    }

    private fun isInsideBracket(text: String, index: Int): Boolean {
        val openIdx = text.lastIndexOf('[', index)
        if (openIdx < 0) return false
        val closeIdx = text.lastIndexOf(']', index)
        return openIdx > closeIdx
    }
}
