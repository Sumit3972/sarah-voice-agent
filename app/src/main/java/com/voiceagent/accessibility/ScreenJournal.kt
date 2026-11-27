package com.voiceagent.accessibility

import java.util.Collections

/**
 * Compresses earlier GUI observations into 1-line chronological step digests so multi-step UI
 * navigation stays lean inside context without flooding tokens with stale trees.
 */
class ScreenJournal {
    private val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun record(packageName: String, summary: String) {
        val line = "[$packageName] ${summary.replace('\n', ' ').trim().take(120)}"
        synchronized(entries) {
            entries += line
            if (entries.size > 12) {
                entries.removeAt(0)
            }
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    fun formatCompact(): String {
        synchronized(entries) {
            if (entries.isEmpty()) return ""
            return entries.joinToString("\n")
        }
    }
}
