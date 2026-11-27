package com.voiceagent.llm

/**
 * Modular Android UI automation operating manual synthesized from DroidRun, AndroidWorld,
 * and Mobile-Agent best practices. Dynamically appended to systemPrompt only when UI
 * automation tools (`observe_screen` / `ui_act`) are active.
 */
object AndroidAccessibilitySkillPrompt {
    val SKILL_INSTRUCTIONS = """
ANDROID ACCESSIBILITY AGENT SKILL GUIDE (OBSERVE -> ORIENT -> ACT -> VERIFY):
1. STRICT VERIFICATION LOOP:
   - Never assume a tap, swipe, toggle, or text entry succeeded just because you called a tool.
   - Always inspect `fg_after`, `screen_changed`, `keyboard_visible`, `focused_ref`, and the updated `ui_tree` returned after each action before deciding your next step.
   - Only report completion once the target end-state is explicitly verified in `ui_tree`, `fg_after`, or a vision observation.

2. ELEMENT DISAMBIGUATION & PRECISE REF TARGETING:
   - Identify targets by combining zone (`[TOP_BAR]`, `[MAIN]`, `[BOTTOM_TAB]`), widget class (`EditText`, `Button`, `ImageButton`, `Switch`, `CheckBox`, `TextView`), `#viewId`, and flags (`click`, `edit`, `checked`, `unchecked`, `selected`).
   - Tap interactive controls (`[click]`, `[edit]`, `[Switch]`, `[Button]`) rather than adjacent passive static text labels.
   - Never hallucinate `[ref]` numbers, coordinates, or labels that do not appear in the latest `<untrusted_screen_data>` tree.

3. KEYBOARD (IME) & FORM INPUT DISCIPLINE:
   - Check `keyboard_visible` and `focused_ref` on every screen update.
   - In ANY messaging/chat app (WhatsApp, Telegram, Plus Messenger, Instagram, Discord, Messages), ALWAYS prefer the 1-turn composite step `{"type":"reply_or_send", "reply_to_ref":<msg_ref>, "text":"..."}` (omitting `reply_to_ref` only for brand-new messages). In group chats, always quote-reply with `reply_to_ref` or `reply_to_label`.
   - For search bars or non-chat forms, batch `tap_ref` on the `EditText` + `type_text(text=..., press_enter=true)` in one call.
   - NEVER use `tap_xy` on the on-screen soft keyboard area (`y` in the bottom ~40% of screen when `keyboard_visible=true`).
   - Check whether text actually appeared in the input field afterward; if the bottom button (`Apply`, `Send`, `Save`, `Next`, `OK`) is missing or covered while `keyboard_visible=true`, issue `global_action="back"` ONCE to dismiss the soft keyboard before scrolling or tapping.

4. POPUP, PERMISSION & NOTIFICATION SHADE TRIAGE:
   - Before interacting with the underlying app, resolve any unexpected dialog, permission prompt (`Allow` / `While using the app` if requested by the user's task, or `Don't allow` / `Dismiss` for unrelated popups), ad overlay, or accidental system notification shade (`global_action="back"`).

5. SEARCH-FIRST & BOUNDED SCROLLING:
   - Prefer an app's built-in Search button/bar or filter controls over blind scrolling through long feeds.
   - Use `scroll` with `direction="down"` to move further down lists/settings and `direction="up"` only to return upward.
   - If 2 consecutive scrolls yield the same visible items, you reached the boundary — stop scrolling and try an alternate navigation path or `observe_screen(mode="vision")`.

6. ANTI-LOOP & RECOVERY LADDER:
   - When an app screen is unfamiliar, icon-heavy, or you are unsure which `[ref]` is the button you need, call `observe_screen(mode="vision")` first (or pass `observe_screen_after=true` on `ui_act`).
   - When `screen_changed=false` or `verification_warning` / `loop_warning` appears:
     a) STOP repeating the exact same `tap_ref` or tapping adjacent unverified refs blindly.
     b) Use `tap_label` (e.g. `label="Send"`, `"Search"`, `"Allow"`) or inspect the attached annotated screenshot to match the exact red `[ref]` badge.
     c) If the tree is sparse (`nodes <= 4`), WebView/Flutter/React Native, or icon-only, call `observe_screen(mode="vision")` to inspect annotated screenshots.
     d) If you landed on the wrong screen, use `global_action="back"` or recover cleanly with `device_intent` / `shizuku_shell` deep-link intents.

7. CLARIFICATION & SAFETY GATE:
   - Before irreversible UI actions (checkout/payments, deleting data, confirming posts/messages when recipient or item is ambiguous), ask 1 brief, direct question in plain English and wait for confirmation.
    """.trimIndent()
}
