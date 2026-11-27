package com.voiceagent

import com.voiceagent.autonomous.SwarmOrchestrator
import com.voiceagent.autonomous.SwarmSubtask
import com.voiceagent.autonomous.SwarmWorkerRole
import com.voiceagent.llm.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SwarmOrchestratorUnitTest {

    @Test
    fun runParallel_executesHeadlessWorkersConcurrentlyAndSerializesUiWorkers() = runBlocking {
        val activeUiCount = AtomicInteger(0)
        val maxConcurrentUi = AtomicInteger(0)
        val activeTotalCount = AtomicInteger(0)
        val maxConcurrentTotal = AtomicInteger(0)

        val orchestrator = SwarmOrchestrator(
            llmRouter = null,
            maxConcurrency = 4,
            streamTurnOverride = { _, goal, _, _, _ ->
                flow {
                    val total = activeTotalCount.incrementAndGet()
                    maxConcurrentTotal.updateAndGet { maxOf(it, total) }
                    val isUi = goal.startsWith("UI:")
                    if (isUi) {
                        val uiNow = activeUiCount.incrementAndGet()
                        maxConcurrentUi.updateAndGet { maxOf(it, uiNow) }
                    }
                    try {
                        delay(180L)
                        emit(LlmStreamEvent.Completed("done:$goal", "gemini-4-hydrogen-sonar"))
                    } finally {
                        if (isUi) activeUiCount.decrementAndGet()
                        activeTotalCount.decrementAndGet()
                    }
                }
            }
        )

        val tasks = listOf(
            SwarmSubtask(id = "h1", role = SwarmWorkerRole.RESEARCH_WORKER, goal = "weather"),
            SwarmSubtask(id = "h2", role = SwarmWorkerRole.FAST_RESPONDER, goal = "notifs"),
            SwarmSubtask(id = "u1", role = SwarmWorkerRole.UI_OPERATOR, goal = "UI:chat1"),
            SwarmSubtask(id = "u2", role = SwarmWorkerRole.UI_OPERATOR, goal = "UI:chat2")
        )

        val t0 = System.currentTimeMillis()
        val results = orchestrator.runParallel(tasks)
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(4, results.size)
        assertTrue(results.all { it.ok })
        // Two UI workers must never overlap on ScreenLease
        assertEquals(1, maxConcurrentUi.get())
        // Headless workers run in parallel alongside the active UI worker
        assertTrue("Expected >=3 concurrent workers, got ${maxConcurrentTotal.get()}", maxConcurrentTotal.get() >= 3)
        // Total time should be ~360ms (2 serialized UI runs) rather than 4 * 180ms = 720ms
        assertTrue("Expected parallel speedup (< 620ms), took ${elapsed}ms", elapsed < 620L)
    }

    @Test
    fun cancelAllWorkers_abortsInFlightWorkersImmediately() = runBlocking {
        val orchestrator = SwarmOrchestrator(
            llmRouter = null,
            maxConcurrency = 4,
            streamTurnOverride = { _, _, _, _, _ ->
                flow {
                    delay(5_000L)
                    emit(LlmStreamEvent.Completed("should_not_finish", "gemini-4-hydrogen-sonar"))
                }
            }
        )

        val job = async {
            orchestrator.runParallel(
                listOf(
                    SwarmSubtask(id = "w1", role = SwarmWorkerRole.RESEARCH_WORKER, goal = "long task 1"),
                    SwarmSubtask(id = "w2", role = SwarmWorkerRole.FAST_RESPONDER, goal = "long task 2")
                )
            )
        }

        delay(120L)
        val t0 = System.currentTimeMillis()
        orchestrator.cancelAllWorkers("test_stop")
        val cancelled = try {
            val res = job.await()
            res.all { !it.ok }
        } catch (_: CancellationException) {
            true
        }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(cancelled)
        assertFalse("Cancellation took too long: ${elapsed}ms", elapsed > 1000L)
    }
}
