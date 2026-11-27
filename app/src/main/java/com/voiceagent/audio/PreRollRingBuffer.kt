package com.voiceagent.audio

class PreRollRingBuffer(
    private val capacitySamples: Int
) {
    private val buffer = ShortArray(capacitySamples)
    private var writePos = 0
    private var count = 0

    @Synchronized
    fun push(samples: ShortArray, len: Int = samples.size) {
        for (i in 0 until len) {
            buffer[writePos] = samples[i]
            writePos = (writePos + 1) % capacitySamples
            if (count < capacitySamples) count++
        }
    }

    @Synchronized
    fun snapshotAndClear(): ShortArray {
        if (count == 0) return ShortArray(0)
        val out = ShortArray(count)
        val start = if (count < capacitySamples) 0 else writePos
        for (i in 0 until count) {
            out[i] = buffer[(start + i) % capacitySamples]
        }
        count = 0
        writePos = 0
        return out
    }

    @Synchronized
    fun clear() {
        count = 0
        writePos = 0
    }
}
