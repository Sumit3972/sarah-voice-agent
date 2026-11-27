package com.voiceagent.accessibility

import kotlinx.coroutines.delay

object IdleWaiter {
    @Volatile
    private var lastEventAtMs: Long = 0L

    fun onAccessibilityEvent() {
        lastEventAtMs = System.currentTimeMillis()
    }

    fun lastEventTimestampMs(): Long = lastEventAtMs

    suspend fun waitForSettle(quietWindowMs: Long = 170L, timeoutMs: Long = 1400L) {
        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            val quietFor = System.currentTimeMillis() - lastEventAtMs
            if (quietFor >= quietWindowMs && (System.currentTimeMillis() - start) >= 80L) {
                return
            }
            delay(50L)
        }
    }
}
