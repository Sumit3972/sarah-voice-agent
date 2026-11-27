package com.voiceagent.accessibility

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay

object IdleWaiter {
    @Volatile
    private var lastEventAtMs: Long = 0L

    @Volatile
    private var lastStructuralEventAtMs: Long = 0L

    fun onAccessibilityEvent(eventType: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        val now = System.currentTimeMillis()
        lastEventAtMs = now
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastStructuralEventAtMs = now
        }
    }

    fun lastEventTimestampMs(): Long = lastEventAtMs

    suspend fun waitForSettle(
        quietWindowMs: Long = 110L,
        timeoutMs: Long = 450L,
        structuralOnly: Boolean = true
    ) {
        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            val now = System.currentTimeMillis()
            val refTs = if (structuralOnly) lastStructuralEventAtMs else lastEventAtMs
            val quietFor = now - refTs
            if (quietFor >= quietWindowMs && (now - start) >= 60L) {
                return
            }
            delay(40L)
        }
    }
}
