package com.voiceagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceagent.audio.VoiceCaptureForegroundService
import com.voiceagent.config.PipelineConfig
import com.voiceagent.shizuku.ShizukuBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class SaraAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SaraAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    @Volatile
    var lastSnapshot: TreeSnapshot? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(PipelineConfig.LOG_TAG, "[Accessibility] SaraAccessibilityService connected")
        VoiceCaptureForegroundService.startIfPermitted(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            IdleWaiter.onAccessibilityEvent()
        }
    }

    override fun onInterrupt() {
        Log.w(PipelineConfig.LOG_TAG, "[Accessibility] SaraAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentPackageName(): String {
        val dm = resources.displayMetrics
        val minContentHeight = (dm.heightPixels * 0.20).toInt()
        val rect = android.graphics.Rect()
        val appWindowPkg = (windows ?: emptyList())
            .asSequence()
            .filter { w ->
                val pkg = w.root?.packageName?.toString().orEmpty()
                if (pkg.isBlank() || pkg == packageName) return@filter false
                w.getBoundsInScreen(rect)
                val isThinStatusBar = pkg == "com.android.systemui" && rect.height() < minContentHeight
                !isThinStatusBar && (pkg != "com.android.systemui" || isDeviceLocked() || w.isFocused || w.isActive)
            }
            .sortedByDescending { (if (it.isFocused) 4 else 0) + (if (it.isActive) 2 else 0) + (if (it.root?.packageName?.toString() != "com.android.systemui") 1 else 0) }
            .firstOrNull()
            ?.root?.packageName?.toString()

        if (!appWindowPkg.isNullOrBlank()) return appWindowPkg
        return rootInActiveWindow?.packageName?.toString()
            ?: lastSnapshot?.packageName
            ?: "unknown"
    }

    fun observeTree(focusQuery: String? = null): TreeSnapshot {
        val dm = resources.displayMetrics
        val snap = TreeCompressor.compress(
            windows = windows ?: emptyList(),
            rootFallback = rootInActiveWindow,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            focusQuery = focusQuery
        )
        lastSnapshot = snap
        return snap
    }

    suspend fun captureAnnotatedScreenshot(focusQuery: String? = null): Pair<TreeSnapshot, EncodedSoMScreenshot?> {
        val snap = observeTree(focusQuery)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return snap to null
        }

        val deferred = CompletableDeferred<Bitmap?>()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuf = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hwBuf, screenshot.colorSpace)
                        val softCopy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        hwBuf.close()
                        deferred.complete(softCopy)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(PipelineConfig.LOG_TAG, "[Accessibility] takeScreenshot error=$errorCode")
                        deferred.complete(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(PipelineConfig.LOG_TAG, "[Accessibility] takeScreenshot threw: ${e.message}")
            return snap to null
        }

        val bmp = withTimeoutOrNull(2500L) { deferred.await() } ?: return snap to null
        return try {
            val encoded = SetOfMarksRenderer.annotateAndEncodeWebP(bmp, snap.nodes)
            snap to encoded
        } finally {
            bmp.recycle()
        }
    }

    suspend fun tapXy(x: Int, y: Int, longPress: Boolean = false): Boolean {
        val dm = resources.displayMetrics
        // EdgeGuard: avoid top notification drag zone while keeping bottom tab bars clickable
        val safeX = x.coerceIn(12, (dm.widthPixels - 12).coerceAtLeast(32)).toFloat()
        val safeY = y.coerceIn(32, (dm.heightPixels - 20).coerceAtLeast(100)).toFloat()

        val path = Path().apply { moveTo(safeX, safeY) }
        val duration = if (longPress) 580L else 60L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureSuspend(gesture)
        if (!ok && !longPress && ShizukuBridge.isPermissionGranted()) {
            return ShizukuBridge.inputTap(safeX.toInt(), safeY.toInt()).ok
        }
        return ok
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320L): Boolean {
        val dm = resources.displayMetrics
        val sx1 = x1.coerceIn(16, (dm.widthPixels - 16).coerceAtLeast(32)).toFloat()
        val sy1 = y1.coerceIn(48, (dm.heightPixels - 96).coerceAtLeast(96)).toFloat()
        val sx2 = x2.coerceIn(16, (dm.widthPixels - 16).coerceAtLeast(32)).toFloat()
        val sy2 = y2.coerceIn(48, (dm.heightPixels - 96).coerceAtLeast(96)).toFloat()

        val path = Path().apply {
            moveTo(sx1, sy1)
            lineTo(sx2, sy2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(120L, 1200L))
        val ok = dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
        if (!ok && ShizukuBridge.isPermissionGranted()) {
            return ShizukuBridge.inputSwipe(sx1.toInt(), sy1.toInt(), sx2.toInt(), sy2.toInt(), durationMs).ok
        }
        return ok
    }

    fun scrollCoordsForDirection(direction: String): IntArray {
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2
        val h = dm.heightPixels
        return when (direction.lowercase()) {
            "up", "backward" -> intArrayOf(midX, (h * 0.30).toInt(), midX, (h * 0.75).toInt())
            "left" -> intArrayOf((dm.widthPixels * 0.25).toInt(), h / 2, (dm.widthPixels * 0.80).toInt(), h / 2)
            "right" -> intArrayOf((dm.widthPixels * 0.80).toInt(), h / 2, (dm.widthPixels * 0.25).toInt(), h / 2)
            else -> intArrayOf(midX, (h * 0.74).toInt(), midX, (h * 0.26).toInt())
        }
    }

    suspend fun scrollDirection(direction: String, targetRef: UiNodeRef? = null): Boolean {
        if (targetRef != null) {
            val liveNode = findLiveNodeMatching(targetRef)
            if (liveNode != null && liveNode.isScrollable) {
                val action = when (direction.lowercase()) {
                    "up", "backward", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (liveNode.performAction(action)) return true
            }
        }
        val c = scrollCoordsForDirection(direction)
        return swipe(c[0], c[1], c[2], c[3], 320L)
    }

    fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    fun findKeyguardPinDigitCoords(): Map<Char, Pair<Int, Int>> {
        val nodes = collectVisibleNodes()
        val rect = android.graphics.Rect()
        val coords = mutableMapOf<Char, Pair<Int, Int>>()
        for (node in nodes) {
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg != "com.android.systemui") continue
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val txt = node.text?.toString()?.trim().orEmpty()
            val candidate = if (desc.length == 1 && desc[0].isDigit()) desc[0]
            else if (txt.length == 1 && txt[0].isDigit()) txt[0]
            else continue
            if (!node.isClickable) continue
            node.getBoundsInScreen(rect)
            if (rect.width() > 20 && rect.height() > 20) {
                coords[candidate] = rect.centerX() to rect.centerY()
            }
        }
        return coords
    }

    fun readKeyguardStatusText(): String {
        val nodes = collectVisibleNodes()
        val msgs = mutableListOf<String>()
        for (node in nodes) {
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg != "com.android.systemui") continue
            val vid = node.viewIdResourceName.orEmpty()
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (vid.contains("keyguardMessage", ignoreCase = true) ||
                vid.contains("colorSimpleLock", ignoreCase = true) ||
                txt.contains("try again", ignoreCase = true) ||
                txt.contains("locked", ignoreCase = true) ||
                txt.contains("incorrect", ignoreCase = true)
            ) {
                if (txt.isNotBlank()) msgs += txt
                else if (desc.isNotBlank()) msgs += desc
            }
        }
        return msgs.distinct().joinToString(" | ")
    }

    private fun collectVisibleNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (w in windows ?: emptyList()) {
            w.root?.let { collectAll(it, out, 0) }
        }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { collectAll(it, out, 0) }
        }
        return out.filter { it.isVisibleToUser }
    }

    suspend fun tapOrLongPressRef(
        ref: UiNodeRef,
        longPress: Boolean,
        allowFallbackTap: Boolean = true
    ): Boolean {
        if (!allowFallbackTap) {
            if (!longPress && ShizukuBridge.isPermissionGranted()) {
                return ShizukuBridge.inputTap(ref.cx, ref.cy).ok
            }
            return tapXy(ref.cx, ref.cy, longPress = longPress)
        }
        val beforeClickMs = IdleWaiter.lastEventTimestampMs()
        var clickedSemantic = false
        var cur: AccessibilityNodeInfo? = findLiveNodeMatching(ref)
        var hops = 0
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (cur != null && hops < 4) {
            val eligible = if (longPress) cur.isLongClickable else cur.isClickable
            if (eligible && cur.performAction(action)) {
                clickedSemantic = true
                break
            }
            cur = cur.parent
            hops++
        }
        if (clickedSemantic) {
            delay(160L)
            if (IdleWaiter.lastEventTimestampMs() > beforeClickMs) {
                return true
            }
        }
        val gestureOk = tapXy(ref.cx, ref.cy, longPress = longPress)
        delay(180L)
        if (gestureOk && IdleWaiter.lastEventTimestampMs() > beforeClickMs) {
            return true
        }
        if (!longPress && ShizukuBridge.isPermissionGranted()) {
            return ShizukuBridge.inputTap(ref.cx, ref.cy).ok
        }
        return gestureOk
    }

    suspend fun typeIntoRef(ref: UiNodeRef?, text: String, pressEnter: Boolean = false): Pair<Boolean, String> {
        val targetNode = if (ref != null) {
            findLiveNodeMatching(ref)
        } else {
            findFocusedOrEditableNode()
        }
        if (targetNode == null) {
            return false to "No editable field found on current screen"
        }
        if (targetNode.isPassword) {
            return false to "Blocked: cannot type into password fields"
        }
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (ref != null) {
            tapXy(ref.cx, ref.cy, longPress = false)
            delay(120L)
        }

        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!ok) {
            // Clipboard fallback for Jetpack Compose / WebView custom inputs
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sara_input", text))
            ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (ok && pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else if (ok && pressEnter && ShizukuBridge.isPermissionGranted()) {
            ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_ENTER)
        }
        return ok to if (ok) "typed" else "ACTION_SET_TEXT and ACTION_PASTE failed"
    }

    suspend fun performGlobalCommand(action: String): Boolean {
        val norm = action.lowercase()
        val (code, fallbackKeyCode) = when (norm) {
            "back", "go_back", "come_back" -> GLOBAL_ACTION_BACK to KeyEvent.KEYCODE_BACK
            "home", "go_home", "close_app", "exit_app" -> GLOBAL_ACTION_HOME to KeyEvent.KEYCODE_HOME
            "recents", "overview" -> GLOBAL_ACTION_RECENTS to KeyEvent.KEYCODE_APP_SWITCH
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to null
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS to null
            else -> return false
        }
        val beforeEvt = IdleWaiter.lastEventTimestampMs()
        val beforePkg = currentPackageName()
        var ok = performGlobalAction(code)
        if (fallbackKeyCode != null) {
            delay(220L)
            val unchanged = IdleWaiter.lastEventTimestampMs() == beforeEvt && currentPackageName() == beforePkg
            if ((!ok || unchanged) && ShizukuBridge.isPermissionGranted()) {
                ok = ShizukuBridge.inputKeyEvent(fallbackKeyCode).ok || ok
            }
        }
        if (code == GLOBAL_ACTION_HOME) {
            val cur = currentPackageName()
            if (!ok || (cur != "com.android.launcher" && !cur.contains("launcher", ignoreCase = true))) {
                runCatching {
                    startActivity(
                        android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                            addCategory(android.content.Intent.CATEGORY_HOME)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }
        return true
    }

    private fun findLiveNodeMatching(ref: UiNodeRef): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (w in windows ?: emptyList()) {
            w.root?.let { collectAll(it, candidates, 0) }
        }
        if (candidates.isEmpty()) {
            rootInActiveWindow?.let { collectAll(it, candidates, 0) }
        }
        val targetRect = android.graphics.Rect()
        return candidates.firstOrNull { node ->
            if (!node.isVisibleToUser) return@firstOrNull false
            node.getBoundsInScreen(targetRect)
            kotlin.math.abs(targetRect.centerX() - ref.cx) <= 12 &&
                kotlin.math.abs(targetRect.centerY() - ref.cy) <= 12
        }
    }

    private fun findFocusedOrEditableNode(): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { collectAll(it, candidates, 0) }
        return candidates.firstOrNull { it.isVisibleToUser && it.isFocused && it.isEditable }
            ?: candidates.firstOrNull { it.isVisibleToUser && it.isEditable }
    }

    private fun collectAll(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 30 || out.size >= 250) return
        out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAll(it, out, depth + 1) }
        }
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!started) return false
        return withTimeoutOrNull(2000L) { deferred.await() } ?: false
    }
}
