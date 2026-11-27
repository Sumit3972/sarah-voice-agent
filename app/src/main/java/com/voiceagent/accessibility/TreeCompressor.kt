package com.voiceagent.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Collections
import java.util.IdentityHashMap

data class UiNodeRef(
    val refId: Int,
    val zone: String, // "TOP_BAR" | "MAIN" | "BOTTOM_TAB"
    val className: String,
    val label: String,
    val viewId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val selected: Boolean,
    val isPassword: Boolean
) {
    val cx: Int get() = bounds.centerX()
    val cy: Int get() = bounds.centerY()
}

data class TreeSnapshot(
    val timestampMs: Long,
    val packageName: String,
    val nodes: List<UiNodeRef>,
    val treeText: String,
    val keyboardVisible: Boolean = false,
    val focusedRefId: Int? = null
)

object TreeCompressor {
    private const val MAX_RENDERED_NODES = 85
    private val NON_ALNUM = Regex("\\s+")

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val zone: String,
        val zoneOrder: Int,
        val label: String,
        val viewId: String,
        val priority: Int
    )

    fun compress(
        windows: List<AccessibilityWindowInfo>,
        rootFallback: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        focusQuery: String? = null
    ): TreeSnapshot {
        val rawNodes = mutableListOf<AccessibilityNodeInfo>()
        val absorbed = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        val rootsToVisit = mutableListOf<AccessibilityNodeInfo>()

        val fallbackPkg = rootFallback?.packageName?.toString().orEmpty()
        val rectTmp = Rect()
        val minFullWindowH = (screenHeight * 0.22).toInt()
        val nonSystemUiWindows = windows.filter { w ->
            val p = w.root?.packageName?.toString().orEmpty()
            w.getBoundsInScreen(rectTmp)
            p.isNotBlank() &&
                p != "com.android.systemui" &&
                p != "com.voiceagent" &&
                rectTmp.height() >= minFullWindowH
        }
        val hasAppWindow = (fallbackPkg.isNotBlank() && fallbackPkg != "com.android.systemui") ||
            nonSystemUiWindows.isNotEmpty()

        // Prefer focused/active real app or permission dialog windows before falling back to SystemUI
        for (w in nonSystemUiWindows.sortedByDescending { (if (it.isFocused) 2 else 0) + (if (it.isActive) 1 else 0) }) {
            val r = w.root ?: continue
            if (rootsToVisit.none { it == r }) rootsToVisit += r
        }

        if (rootFallback != null && (fallbackPkg != "com.android.systemui" || !hasAppWindow)) {
            if (rootsToVisit.none { it == rootFallback }) {
                rootsToVisit += rootFallback
            }
        }

        for (w in windows) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString().orEmpty()
            w.getBoundsInScreen(rectTmp)
            val isThinBar = pkg == "com.android.systemui" && rectTmp.height() < minFullWindowH
            if (hasAppWindow && (w.type == AccessibilityWindowInfo.TYPE_SYSTEM || pkg == "com.android.systemui" || isThinBar)) {
                continue
            }
            if (!hasAppWindow && isThinBar && windows.any { it.root?.packageName?.toString() == "com.android.systemui" && run { val b = Rect(); it.getBoundsInScreen(b); b.height() >= minFullWindowH } }) {
                continue
            }
            if (rootsToVisit.none { it == r }) {
                rootsToVisit += r
            }
        }
        if (rootsToVisit.isEmpty() && rootFallback != null) {
            rootsToVisit += rootFallback
        }

        val keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val activePkg = rootsToVisit.firstOrNull { !it.packageName.isNullOrBlank() }?.packageName?.toString().orEmpty()
        for (root in rootsToVisit) {
            collectNodesDfs(root, rawNodes, absorbed, screenWidth, screenHeight, depth = 0)
        }

        val topThreshold = (screenHeight * 0.14).toInt()
        val bottomThreshold = (screenHeight * 0.86).toInt()
        val queryTokens = focusQuery?.trim()?.lowercase()
            ?.split(Regex("[\\s_]+"))
            ?.filter { it.length >= 2 }
            .orEmpty()

        val candidates = mutableListOf<Candidate>()
        for (node in rawNodes) {
            if (absorbed.contains(node)) continue
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val label = extractLabel(node)
            val viewId = shortViewId(node)
            val isInteractive = node.isClickable ||
                node.isLongClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable ||
                node.isSelected
            if (label.isBlank() && !isInteractive) {
                continue
            }

            val cy = rect.centerY()
            val (zone, zoneOrder) = when {
                cy <= topThreshold -> "TOP_BAR" to 0
                cy >= bottomThreshold -> "BOTTOM_TAB" to 2
                else -> "MAIN" to 1
            }

            val lblLower = label.lowercase()
            val idLower = viewId.lowercase()
            var prio = 0
            if (queryTokens.isNotEmpty() && queryTokens.any { lblLower.contains(it) || idLower.contains(it) }) {
                prio += 20
            }
            if (zone == "TOP_BAR" || zone == "BOTTOM_TAB") prio += 10
            if (node.isEditable) prio += 12
            if (node.isClickable) prio += 4
            if (idLower.contains("send") || idLower.contains("submit") || idLower.contains("search") ||
                idLower.contains("done") || idLower.contains("confirm") || idLower.contains("apply") ||
                lblLower in setOf("send", "submit", "reply", "search", "done", "ok", "apply", "save", "next", "confirm", "continue")
            ) {
                prio += 12
            }
            if (node.isSelected || node.isCheckable) prio += 2
            if (label.isNotBlank()) prio += 1

            candidates += Candidate(
                node = node,
                rect = rect,
                zone = zone,
                zoneOrder = zoneOrder,
                label = label,
                viewId = viewId,
                priority = prio
            )
        }

        // Select top budget candidates ensuring TOP_BAR / BOTTOM_TAB stay intact, then restore natural reading order
        val selected = candidates
            .sortedByDescending { it.priority }
            .take(MAX_RENDERED_NODES)
            .sortedWith(compareBy<Candidate>({ it.zoneOrder }, { it.rect.top / 36 }, { it.rect.left }))

        val refs = mutableListOf<UiNodeRef>()
        var refCounter = 1
        var focusedRefId: Int? = null
        for (c in selected) {
            val isPassword = c.node.isPassword
            @Suppress("DEPRECATION")
            val checkedState = if (c.node.isCheckable) c.node.isChecked else null
            val assignedRef = refCounter++
            if (focusedRefId == null && (c.node.isFocused || c.node.isAccessibilityFocused) && (c.node.isEditable || c.node.isClickable)) {
                focusedRefId = assignedRef
            }
            refs += UiNodeRef(
                refId = assignedRef,
                zone = c.zone,
                className = shortClass(c.node.className?.toString()),
                label = if (isPassword) "[PASSWORD_FIELD]" else c.label,
                viewId = c.viewId,
                bounds = c.rect,
                clickable = c.node.isClickable,
                longClickable = c.node.isLongClickable,
                editable = c.node.isEditable,
                scrollable = c.node.isScrollable,
                checked = checkedState,
                selected = c.node.isSelected,
                isPassword = isPassword
            )
        }

        val sb = StringBuilder()
        sb.append("<untrusted_screen_data>\n")
        sb.append("package=").append(activePkg.ifBlank { "unknown" })
            .append(" screen=").append(screenWidth).append("x").append(screenHeight)
            .append(" nodes=").append(refs.size)
            .append(" keyboard=").append(keyboardVisible)
        if (focusedRefId != null) {
            sb.append(" focused=[").append(focusedRefId).append("]")
        }
        sb.append("\n")

        for (n in refs) {
            val flags = mutableListOf<String>()
            if (n.clickable) flags += "click"
            if (n.longClickable) flags += "long"
            if (n.editable) flags += "edit"
            if (n.scrollable) flags += "scroll"
            if (n.selected) flags += "selected"
            if (n.checked != null) flags += if (n.checked) "checked" else "unchecked"
            if (n.isPassword) flags += "PASSWORD"

            sb.append("[").append(n.refId).append("] [").append(n.zone).append("] ")
                .append(n.className)
            if (n.label.isNotBlank()) {
                sb.append(" \"").append(n.label.replace("\"", "'").take(90)).append("\"")
            }
            if (n.viewId.isNotBlank()) {
                sb.append(" #").append(n.viewId)
            }
            sb.append(" @(").append(n.cx).append(",").append(n.cy).append(")")
            if (flags.isNotEmpty()) {
                sb.append(" [").append(flags.joinToString(",")).append("]")
            }
            sb.append("\n")
        }
        sb.append("</untrusted_screen_data>")

        return TreeSnapshot(
            timestampMs = System.currentTimeMillis(),
            packageName = activePkg.ifBlank { "unknown" },
            nodes = refs,
            treeText = sb.toString(),
            keyboardVisible = keyboardVisible,
            focusedRefId = focusedRefId
        )
    }

    private fun collectNodesDfs(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        absorbed: MutableSet<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int
    ) {
        if (depth > 34 || out.size >= 260) return
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val inScreen = rect.width() > 2 && rect.height() > 2 &&
            rect.right > 0 && rect.bottom > 0 &&
            rect.left < screenWidth && rect.top < screenHeight

        if (inScreen) {
            val directLabel = directNodeLabel(node)
            val viewId = shortViewId(node)
            val interactive = node.isClickable ||
                node.isLongClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable ||
                node.isSelected

            if (interactive || directLabel.isNotBlank()) {
                out += node
                // Deduplicate: only absorb leaf descriptions if compact container has <=3 children, no interactive descendants (depth <=4), and <=1 labeled child
                if (interactive && node.childCount in 1..3 &&
                    !hasInteractiveDescendant(node, 0) &&
                    countDistinctLabeledChildren(node) <= 1
                ) {
                    markDescendantsAbsorbed(node, absorbed, 0)
                }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectNodesDfs(child, out, absorbed, screenWidth, screenHeight, depth + 1)
        }
    }

    private fun countDistinctLabeledChildren(node: AccessibilityNodeInfo): Int {
        val labels = LinkedHashSet<String>()
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val lbl = directNodeLabel(c)
            if (lbl.isNotBlank()) labels += lbl.lowercase()
        }
        return labels.size
    }

    private fun hasInteractiveDescendant(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth >= 4) return false
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (c.isClickable || c.isEditable || c.isLongClickable || c.isScrollable) return true
            if (hasInteractiveDescendant(c, depth + 1)) return true
        }
        return false
    }

    private fun markDescendantsAbsorbed(node: AccessibilityNodeInfo, absorbed: MutableSet<AccessibilityNodeInfo>, depth: Int) {
        if (depth >= 2) return
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            absorbed += c
            markDescendantsAbsorbed(c, absorbed, depth + 1)
        }
    }

    private fun extractLabel(node: AccessibilityNodeInfo): String {
        val direct = directNodeLabel(node)
        if (direct.isNotBlank()) return direct
        if (node.isClickable || node.isSelected || node.isCheckable || node.childCount in 1..4) {
            val childParts = LinkedHashSet<String>()
            rollUpChildLabels(node, childParts, depth = 0)
            if (childParts.isNotEmpty()) {
                return childParts.take(2).joinToString(" • ").take(90)
            }
        }
        return ""
    }

    private fun rollUpChildLabels(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth >= 2 || out.size >= 2) return
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val lbl = directNodeLabel(c)
            if (lbl.isNotBlank()) {
                out += lbl
                if (out.size >= 2) return
            } else {
                rollUpChildLabels(c, out, depth + 1)
            }
        }
    }

    private fun directNodeLabel(node: AccessibilityNodeInfo): String {
        val textStr = node.text?.toString()?.trim().orEmpty()
        val descStr = node.contentDescription?.toString()?.trim().orEmpty()
        val hintStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""

        // Prefer richer contentDescription on controls like DatePicker day cells ("01 December 2026" vs "1")
        val raw = when {
            descStr.isNotBlank() && textStr.isNotBlank() -> {
                if (descStr.length > textStr.length + 2 && (textStr.length <= 4 || descStr.contains(textStr, ignoreCase = true))) {
                    descStr
                } else if (descStr.equals(textStr, ignoreCase = true)) {
                    textStr
                } else {
                    "$textStr • $descStr"
                }
            }
            textStr.isNotBlank() -> textStr
            descStr.isNotBlank() -> descStr
            else -> hintStr
        }
        return raw.replace(NON_ALNUM, " ")
    }

    private fun shortClass(raw: String?): String {
        if (raw.isNullOrBlank()) return "View"
        return raw.substringAfterLast('.').ifBlank { raw }
    }

    private fun shortViewId(node: AccessibilityNodeInfo): String {
        val id = node.viewIdResourceName ?: return ""
        return id.substringAfter(":id/", id)
    }
}
