package com.voiceagent.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.voiceagent.audio.VoiceCaptureForegroundService
import com.voiceagent.config.PipelineConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

data class CachedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val hasReplyAction: Boolean,
    val replyAction: Notification.Action?
)

class SaraNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: SaraNotificationListenerService? = null
            private set

        @Volatile
        var speakIncomingEnabled: Boolean = false

        val activeCache = ConcurrentHashMap<String, CachedNotification>()
        private val lastEmittedSignatureByKey = ConcurrentHashMap<String, Pair<String, Long>>()

        private val _incomingEvents = MutableSharedFlow<CachedNotification>(extraBufferCapacity = 32)
        val incomingEvents: SharedFlow<CachedNotification> = _incomingEvents.asSharedFlow()

        fun injectDebugNotification(notif: CachedNotification) {
            activeCache[notif.key] = notif
            lastEmittedSignatureByKey[notif.key] = "${notif.title}|${notif.text}" to System.currentTimeMillis()
            _incomingEvents.tryEmit(notif)
        }

        fun isConnected(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val cn = ComponentName(context, SaraNotificationListenerService::class.java)
            return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
        }

        fun listRecent(limit: Int = 10): List<CachedNotification> {
            return activeCache.values
                .sortedByDescending { it.postTime }
                .take(limit.coerceIn(1, 30))
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeCache.clear()
        runCatching {
            activeNotifications?.forEach { cacheSbn(it) }
        }
        Log.i(PipelineConfig.LOG_TAG, "[Notifications] SaraNotificationListenerService connected (cached=${activeCache.size})")
        VoiceCaptureForegroundService.startIfPermitted(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        cacheSbn(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        activeCache.remove(sbn.key)
    }

    fun sendInlineReply(queryOrKey: String, message: String): Pair<Boolean, String> {
        val target = findMatchingNotification(queryOrKey)
            ?: return false to "No matching active notification found for '$queryOrKey'"
        val action = target.replyAction
            ?: return false to "${target.appLabel} notification from '${target.title}' has no inline reply action"
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) {
            return false to "No RemoteInput field on notification"
        }

        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, message)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(applicationContext, 0, intent)
            Log.i(PipelineConfig.LOG_TAG, "[Notifications] Sent inline RemoteInput reply to ${target.title} (${target.appLabel})")
            true to "Sent reply to ${target.title} via ${target.appLabel}"
        } catch (e: Exception) {
            false to "Failed sending inline reply: ${e.message}"
        }
    }

    fun dismissMatching(queryOrKey: String): Pair<Boolean, String> {
        val target = findMatchingNotification(queryOrKey)
            ?: return false to "No matching notification for '$queryOrKey'"
        return try {
            cancelNotification(target.key)
            activeCache.remove(target.key)
            true to "Dismissed ${target.appLabel} notification from ${target.title}"
        } catch (e: Exception) {
            false to "Dismiss failed: ${e.message}"
        }
    }

    private fun findMatchingNotification(queryOrKey: String): CachedNotification? {
        activeCache[queryOrKey]?.let { return it }
        val q = queryOrKey.trim().lowercase()
        val sorted = activeCache.values.sortedByDescending { it.postTime }
        if (q.isBlank()) return sorted.firstOrNull { it.hasReplyAction } ?: sorted.firstOrNull()
        return sorted.firstOrNull {
            it.title.lowercase().contains(q) ||
                it.appLabel.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q) ||
                it.text.lowercase().contains(q)
        }
    }

    private fun cacheSbn(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val replyAct = notif.actions?.firstOrNull { act ->
            val ri = act.remoteInputs
            ri != null && ri.isNotEmpty()
        }
        val isBackgroundServiceNotice = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            replyAct == null &&
            (sbn.packageName == packageName || sbn.packageName == "android" || sbn.packageName == "com.android.systemui")
        if (isBackgroundServiceNotice) return
        val appLabel = resolveAppLabel(sbn.packageName)
        val cached = CachedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postTime = sbn.postTime,
            hasReplyAction = replyAct != null,
            replyAction = replyAct
        )
        activeCache[sbn.key] = cached

        val sig = "$title|$text"
        val now = System.currentTimeMillis()
        val prev = lastEmittedSignatureByKey[sbn.key]
        if (prev == null || prev.first != sig || (now - prev.second) > 15_000L) {
            lastEmittedSignatureByKey[sbn.key] = sig to now
            _incomingEvents.tryEmit(cached)
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        return runCatching {
            val ai = packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            packageManager.getApplicationLabel(ai).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
    }
}
