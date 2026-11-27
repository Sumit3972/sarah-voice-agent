package com.voiceagent

import com.voiceagent.accessibility.AppShortcutStore
import com.voiceagent.autonomous.AutonomousTaskHelpers
import com.voiceagent.autonomous.DirectiveType
import com.voiceagent.llm.AccountState
import com.voiceagent.llm.MercuryDecideRouter
import com.voiceagent.llm.RouteDecisionTarget
import com.voiceagent.memory.AutonomousTaskEntity
import com.voiceagent.notifications.CachedNotification
import com.voiceagent.orchestrator.LocalFastPathMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousEngineUnitTest {

    @Test
    fun matchesNotification_respectsModeStatusPackageAndSenderAliases() {
        val chatTask = AutonomousTaskEntity(
            id = 1L,
            title = "Chat with Priya",
            mode = AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP,
            goalPrompt = "Reply politely",
            status = AutonomousTaskHelpers.STATUS_ACTIVE,
            watchPackage = "com.whatsapp",
            watchSender = "Priya, Priya Sharma"
        )

        val matching = CachedNotification(
            key = "k1",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Priya Sharma",
            text = "Hey, are you joining?",
            postTime = 1000L,
            hasReplyAction = true,
            replyAction = null
        )
        assertTrue(AutonomousTaskHelpers.matchesNotification(chatTask, matching))

        val wrongSender = matching.copy(title = "Rahul Verma")
        assertFalse(AutonomousTaskHelpers.matchesNotification(chatTask, wrongSender))

        val wrongApp = matching.copy(packageName = "com.instagram.android", appLabel = "Instagram")
        assertFalse(AutonomousTaskHelpers.matchesNotification(chatTask, wrongApp))

        val pausedTask = chatTask.copy(status = AutonomousTaskHelpers.STATUS_PAUSED)
        assertFalse(AutonomousTaskHelpers.matchesNotification(pausedTask, matching))

        val watchTask = chatTask.copy(mode = AutonomousTaskHelpers.MODE_INTERVAL_WATCH)
        assertFalse(AutonomousTaskHelpers.matchesNotification(watchTask, matching))
    }

    @Test
    fun parseDirective_extractsCompleteNotifyNeedUserAndIdleTags() {
        val complete = AutonomousTaskHelpers.parseDirective(
            "Checked Delhi weather and notified.\n[TASK_COMPLETE: Delhi is 24 degrees and clear.]"
        )
        assertEquals(DirectiveType.COMPLETE, complete.type)
        assertEquals("Delhi is 24 degrees and clear.", complete.message)
        assertEquals("Checked Delhi weather and notified.", complete.cleanBody)

        val notify = AutonomousTaskHelpers.parseDirective(
            "Battery dropped.\n[TASK_NOTIFY: Battery is now at 25 percent.]"
        )
        assertEquals(DirectiveType.NOTIFY, notify.type)
        assertEquals("Battery is now at 25 percent.", notify.message)

        val needUser = AutonomousTaskHelpers.parseDirective(
            "She asked for your UPI PIN.\n[TASK_NEED_USER: Priya asked for a payment transfer; confirm how to reply.]"
        )
        assertEquals(DirectiveType.NEED_USER, needUser.type)
        assertTrue(needUser.message.contains("Priya"))

        val idle = AutonomousTaskHelpers.parseDirective("Battery is 82%, all good.\n[TASK_IDLE]")
        assertEquals(DirectiveType.IDLE, idle.type)
        assertEquals("", idle.message)
    }

    @Test
    fun scratchpadAndMaxRuns_enforceBounds() {
        var pad = ""
        repeat(60) { i ->
            pad = AutonomousTaskHelpers.appendScratchpad(pad, "Tick #$i sent reply message number $i to Priya cleanly.", maxChars = 600)
        }
        assertTrue(pad.length <= 600)
        assertTrue(pad.contains("Tick #59"))

        val atCap = AutonomousTaskEntity(
            id = 2L,
            title = "Cap test",
            mode = AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP,
            goalPrompt = "Chat",
            maxRuns = 40,
            runCount = 40
        )
        assertTrue(AutonomousTaskHelpers.shouldPauseForMaxRuns(atCap))
        assertFalse(AutonomousTaskHelpers.shouldPauseForMaxRuns(atCap.copy(runCount = 39)))
    }

    @Test
    fun router_routesAutonomousCommandsToAutonomousTaskToolAndGeminiSmart() {
        val router = MercuryDecideRouter()
        val chatTools = router.inferKeywordTools("Chat with Priya on WhatsApp on behalf of me until I say stop")
        assertTrue("autonomous_task" in chatTools)

        val watchTools = router.inferKeywordTools("Check my battery level every 10 minutes and tell me if it drops below 20")
        assertTrue("autonomous_task" in watchTools)

        val stopTools = router.inferKeywordTools("Stop all background tasks")
        assertTrue("autonomous_task" in stopTools)

        val scheduledTools = router.inferKeywordTools("Wake up at 7 PM and turn on DND")
        assertTrue("autonomous_task" in scheduledTools)

        val bundleTools = router.mapBundleToTools("autonomous_automation", needsVision = false, userUtterance = "Chat with Priya")
        assertTrue("autonomous_task" in bundleTools)

        val (target, effort) = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "autonomous_automation",
            rawLevel = "instant",
            mergedTools = setOf("autonomous_task"),
            needsVision = false,
            activePackage = "",
            userUtterance = "Chat with Priya on behalf of me"
        )
        assertEquals(RouteDecisionTarget.GEMINI_SMART, target)
        assertEquals("LOW", effort)

        val swarmTools = router.inferKeywordTools("Check weather and battery simultaneously in parallel")
        assertTrue("swarm_dispatch" in swarmTools)
    }

    @Test
    fun localFastPath_matchesSingularAndPluralStopAllTaskAndShortcutStoreCaches() = runBlocking {
        val singular = LocalFastPathMatcher.match("stop all task")
        assertNotNull(singular)
        assertEquals("autonomous_task", singular?.toolName)
        assertEquals("stop_all_tasks", singular?.action)

        val plural = LocalFastPathMatcher.match("Sarah please stop all background tasks")
        assertNotNull(plural)
        assertEquals("stop_all_tasks", plural?.action)

        val store = AppShortcutStore(dao = null)
        store.recordSendShortcut(
            packageName = "org.telegram.plus",
            inputViewId = "chat_text_entry",
            sendViewId = "chat_send_button",
            sendLabel = "Send",
            replyMode = "swipe_right"
        )
        val cached = store.get("org.telegram.plus")
        assertNotNull(cached)
        assertEquals("chat_send_button", cached?.sendViewId)
        assertEquals("swipe_right", cached?.replyMode)

        val acc = AccountState(email = "single@example.com", refreshToken = "rt")
        assertEquals(1, acc.inFlightCount.incrementAndGet())
        assertEquals(2, acc.inFlightCount.incrementAndGet())
        assertEquals(1, acc.inFlightCount.decrementAndGet())
    }
}
