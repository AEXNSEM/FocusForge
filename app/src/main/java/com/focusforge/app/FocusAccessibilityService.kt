package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * FocusAccessibilityService
 *
 * IMPORTANT ARCHITECTURAL NOTE:
 * This service and MainActivity run in the SAME process (no android:process is
 * declared in the manifest) and, absent an explicit background thread, accessibility
 * callbacks are dispatched on that process's MAIN thread — the identical thread Compose
 * uses to render MainActivity's UI. Anything expensive done in onAccessibilityEvent()
 * therefore directly steals frame time from the countdown screens, which is the root
 * cause of the "timers desync / freeze" and "IME chokes" symptoms as much as it is a
 * battery issue. The fixes below are built around keeping this hot path O(1) in the
 * common case, not just "add a throttle".
 */
class FocusAccessibilityService : AccessibilityService() {

    // ---- Tunables -----------------------------------------------------------------
    private companion object {
        const val PREFS_NAME = "focus_forge_prefs"
        const val KEY_BLOCKED_SET = "blocked_packages_set"
        const val KEY_QUARANTINE_END_PREFIX = "quarantine_end_"
        const val KEY_LAST_BREACH_PREFIX = "last_breach_timestamp_"
        const val KEY_SESSION_AUTHORIZED_PREFIX = "session_authorized_"
        const val KEY_SESSION_START_PREFIX = "session_start_"
        const val KEY_SESSION_LAST_ACTIVE_PREFIX = "session_last_active_"
        const val KEY_CHECKPOINT_DISMISSED_PREFIX = "checkpoint_dismissed_"

        val DEFAULT_BLOCKED = setOf("com.android.chrome")

        const val SESSION_THROTTLE_MS = 1_500L
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        const val DRIFT_CHECKPOINT_MS = 45 * 60 * 1000L
        const val QUARANTINE_FIRST_BREACH_MS = 1 * 60 * 60 * 1000L
        const val QUARANTINE_REPEAT_BREACH_MS = 4 * 60 * 60 * 1000L
        const val REPEAT_BREACH_WINDOW_MS = 24 * 60 * 60 * 1000L

        // Bounds for the ONE tree walk we still do (on window-state / navigation changes).
        const val MAX_TRAVERSAL_DEPTH = 40
        const val MAX_NODES_PER_SCAN = 400
    }

    // Word-boundary patterns so e.g. "assessment" or "popcorn" never match.
    private val breachPatterns = listOf(
        Pattern.compile("\\b(porn|xvideos|xnxx|pornhub|xhamster|redtube|erotic|nsfw)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(incognito|private browsing)\\b", Pattern.CASE_INSENSITIVE)
    )

    private var lastSessionCheckTime = 0L
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // =================================================================================
    // Lifecycle: dynamic package filtering so the OS stops dispatching events for apps
    // we don't even track, instead of us paying for a SharedPreferences read + Set
    // lookup on every window event from every app on the device.
    // =================================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        applyPackageFilter()

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BLOCKED_SET) applyPackageFilter()
        }
        prefsListener = listener
        prefs().registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        prefsListener?.let { prefs().unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        return super.onUnbind(intent)
    }

    private fun applyPackageFilter() {
        val blocked = prefs().getStringSet(KEY_BLOCKED_SET, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        val info = serviceInfo ?: AccessibilityServiceInfo()
        // null = "all packages" (fallback if the user has unblocked everything).
        info.packageNames = if (blocked.isEmpty()) null else blocked.toTypedArray()
        serviceInfo = info
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // =================================================================================
    // Hot path
    // =================================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val prefs = prefs()
        val blockedPackages = prefs.getStringSet(KEY_BLOCKED_SET, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        if (!blockedPackages.contains(packageName)) return

        val currentTime = System.currentTimeMillis()

        // 1. Quarantine gate. Cheap (single Long read) — always evaluated, never throttled.
        val quarantineEnd = prefs.getLong(KEY_QUARANTINE_END_PREFIX + packageName, 0L)
        if (currentTime < quarantineEnd) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchQuarantineScreen(packageName, quarantineEnd)
            return
        }

        // 2. Trapwire breach scan. Intentionally NOT throttled (this is the one check
        // that must never lag), but scoped so it stays cheap regardless of frequency:
        //  - TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_TEXT_SELECTION_CHANGED: inspect exactly
        //    the node the user is typing into. No traversal at all — O(1).
        //  - TYPE_WINDOW_STATE_CHANGED: a bounded, editable-only sweep to catch a URL
        //    that changed programmatically (autofill, paste, deep link) without a
        //    text-changed event of its own.
        //  - Everything else (notably TYPE_WINDOW_CONTENT_CHANGED, which fires on
        //    every scroll/DOM tick): skipped entirely. This is the fix for the
        //    battery drain, the IME lag, and the frozen-looking countdowns — those
        //    were all downstream of a full node-tree walk running on every pixel of
        //    scroll.
        if (detectsBreach(event)) {
            triggerQuarantineBreach(packageName, currentTime)
            return
        }

        // 3. Session bookkeeping (idle timeout / drift checkpoint). Throttled — this is
        // pure liveness tracking, not enforcement, so it's safe to coalesce.
        if ((currentTime - lastSessionCheckTime) < SESSION_THROTTLE_MS) return
        lastSessionCheckTime = currentTime
        handleSessionState(packageName, currentTime, prefs)
    }

    private fun handleSessionState(packageName: String, currentTime: Long, prefs: SharedPreferences) {
        val lastActive = prefs.getLong(KEY_SESSION_LAST_ACTIVE_PREFIX + packageName, 0L)
        val sessionStart = prefs.getLong(KEY_SESSION_START_PREFIX + packageName, 0L)
        val isAuthorized = prefs.getBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)

        // Inactivity check: idle for > 10 minutes silently expires the session.
        if (isAuthorized && (currentTime - lastActive) > IDLE_TIMEOUT_MS) {
            prefs.edit()
                .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
                .remove(KEY_SESSION_START_PREFIX + packageName)
                .remove(KEY_CHECKPOINT_DISMISSED_PREFIX + packageName)
                .apply()
            launchLockoutScreen(packageName)
            return
        }

        if (!isAuthorized) {
            launchLockoutScreen(packageName)
            return
        }

        // 45-minute continuous drift check.
        val continuousDuration = currentTime - sessionStart
        val checkpointCleared = prefs.getBoolean(KEY_CHECKPOINT_DISMISSED_PREFIX + packageName, false)
        if (continuousDuration >= DRIFT_CHECKPOINT_MS && !checkpointCleared) {
            launchDriftCheckpointScreen(packageName)
            return
        }

        prefs.edit().putLong(KEY_SESSION_LAST_ACTIVE_PREFIX + packageName, currentTime).apply()
    }

    // =================================================================================
    // Breach detection — scoped to editable input only, never page text/labels/menus.
    // =================================================================================

    private fun detectsBreach(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source
                try {
                    source != null && isLikelyInputField(source) && matchesTypedText(source)
                } finally {
                    recycleIfNeeded(source)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val root = rootInActiveWindow
                try {
                    scanEditableNodes(root, NodeBudget(MAX_NODES_PER_SCAN))
                } finally {
                    recycleIfNeeded(root)
                }
            }
            else -> false
        }
    }

    /**
     * Bounded, depth- and count-limited walk that only ever inspects editable nodes'
     * TEXT (never contentDescription — icon/button labels are not something the user
     * typed, and scanning them is the single biggest source of false-positive
     * quarantines). Recycles every child node it obtains once done with it.
     */
    private fun scanEditableNodes(node: AccessibilityNodeInfo?, budget: NodeBudget, depth: Int = 0): Boolean {
        if (node == null || depth > MAX_TRAVERSAL_DEPTH || budget.remaining <= 0) return false
        budget.remaining--

        if (isLikelyInputField(node) && matchesTypedText(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = try {
                scanEditableNodes(child, budget, depth + 1)
            } finally {
                recycleIfNeeded(child)
            }
            if (hit) return true
        }
        return false
    }

    /**
     * The core false-positive fix: only nodes the user can actually type into (address
     * bars, in-page search boxes) are eligible. Static page text, tab titles, bookmark
     * labels, and menu items are never editable and are therefore never inspected —
     * so a news article or a settings menu that happens to contain a matched word can
     * no longer trigger a breach.
     */
    private fun isLikelyInputField(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString().orEmpty()
        return className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun matchesTypedText(node: AccessibilityNodeInfo): Boolean {
        val typed = node.text?.toString().orEmpty()
        if (typed.isBlank()) return false
        return breachPatterns.any { it.matcher(typed).find() }
    }

    private fun recycleIfNeeded(node: AccessibilityNodeInfo?) {
        // recycle() is a documented no-op from API 33 onward; calling it on newer
        // versions is harmless but pointless, and on some OEM builds double-recycling
        // a cached node throws, so we gate it explicitly rather than relying on the
        // no-op behavior everywhere.
        if (node != null && Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private class NodeBudget(var remaining: Int)

    // =================================================================================
    // Quarantine enforcement
    // =================================================================================

    private fun triggerQuarantineBreach(packageName: String, currentTime: Long) {
        val prefs = prefs()
        val lastBreachTime = prefs.getLong(KEY_LAST_BREACH_PREFIX + packageName, 0L)
        val isRepeatBreach = (currentTime - lastBreachTime) < REPEAT_BREACH_WINDOW_MS

        val penaltyMillis = if (isRepeatBreach) QUARANTINE_REPEAT_BREACH_MS else QUARANTINE_FIRST_BREACH_MS
        val quarantineEnd = currentTime + penaltyMillis

        // commit() (synchronous) rather than apply() here deliberately: this is the one
        // write in the whole app that MUST survive an immediate process death — it's
        // rare (at most once per breach) so the small synchronous cost is a non-issue.
        prefs.edit()
            .putLong(KEY_QUARANTINE_END_PREFIX + packageName, quarantineEnd)
            .putLong(KEY_LAST_BREACH_PREFIX + packageName, currentTime)
            .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
            .remove(KEY_SESSION_START_PREFIX + packageName)
            .remove(KEY_CHECKPOINT_DISMISSED_PREFIX + packageName)
            .commit()

        performGlobalAction(GLOBAL_ACTION_HOME)
        launchQuarantineScreen(packageName, quarantineEnd)
    }

    // =================================================================================
    // Screen launches
    // =================================================================================

    private fun launchLockoutScreen(packageName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_GATE_ENTRY)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
        })
    }

    private fun launchDriftCheckpointScreen(packageName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_DRIFT_CHECKPOINT)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
        })
    }

    private fun launchQuarantineScreen(packageName: String, quarantineEnd: Long) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_QUARANTINE_HAMMER)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_QUARANTINE_END, quarantineEnd)
        })
    }

    override fun onInterrupt() {}
}

// Shared intent-extra contract with MainActivity. Kept as top-level consts (rather than
// duplicated string literals) so a typo in one file fails to compile instead of silently
// failing to match at runtime. In a larger project these belong in their own file shared
// by both build targets.
const val EXTRA_MODE = "MODE"
const val EXTRA_TRIGGERED_BY = "TRIGGERED_BY"
const val EXTRA_QUARANTINE_END = "QUARANTINE_END"
const val MODE_GATE_ENTRY = "GATE_ENTRY"
const val MODE_DRIFT_CHECKPOINT = "DRIFT_CHECKPOINT"
const val MODE_QUARANTINE_HAMMER = "QUARANTINE_HAMMER"
const val MODE_DASHBOARD = "DASHBOARD"
