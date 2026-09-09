package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
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
 * therefore directly steals frame time from the countdown screens. The hot path below is
 * built around staying O(1) in the common case, not just "add a throttle".
 *
 * Two independent, non-interacting penalty tracks are enforced here:
 *  - The Trapwire Quarantine Hammer (this file): explicit-content breach OR incognito/
 *    private-browsing detection → immediate GLOBAL_ACTION_HOME + a 1hr/4hr lock.
 *  - Progressive Drift Escalation (this file): pure continuous-usage duration, in three
 *    rising tiers, culminating in a 15-minute cool-off that is deliberately a SEPARATE
 *    mechanism (separate prefs keys, separate screen) from the Quarantine Hammer — an
 *    overuse cool-off and a content-violation lock must never be presented, logged, or
 *    reasoned about as the same thing.
 * The Home-Bypass Surcharge (Gate 1 reading-cooldown dodging) lives entirely in
 * MainActivity.kt and never escalates into either of the tracks here.
 */
class FocusAccessibilityService : AccessibilityService() {

    // ---- Tunables -----------------------------------------------------------------
    private companion object {
        const val TAG = "FocusForgeService"

        const val PREFS_NAME = "focus_forge_prefs"
        const val KEY_BLOCKED_SET = "blocked_packages_set"
        const val KEY_QUARANTINE_END_PREFIX = "quarantine_end_"
        const val KEY_LAST_BREACH_PREFIX = "last_breach_timestamp_"
        const val KEY_SESSION_AUTHORIZED_PREFIX = "session_authorized_"
        const val KEY_SESSION_START_PREFIX = "session_start_"
        const val KEY_SESSION_LAST_ACTIVE_PREFIX = "session_last_active_"
        const val KEY_DRIFT_LEVEL_PREFIX = "drift_level_"
        const val KEY_DRIFT_COOLOFF_END_PREFIX = "drift_cooloff_end_"
        const val KEY_READING_END_PREFIX = "reading_end_time_"

        val DEFAULT_BLOCKED = setOf("com.android.chrome")

        const val SESSION_THROTTLE_MS = 1_500L
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        // Progressive Drift Escalation thresholds — cumulative continuous-usage duration
        // measured from a single, un-reset session_start (see handleSessionState()).
        const val DRIFT_CHECKPOINT_1_MS = 45 * 60 * 1000L   // 30s Intent Anchor
        const val DRIFT_CHECKPOINT_2_MS = 90 * 60 * 1000L   // 60s pause + re-verification quiz
        const val DRIFT_CHECKPOINT_3_MS = 135 * 60 * 1000L  // mandatory cool-off
        const val DRIFT_COOLOFF_DURATION_MS = 15 * 60 * 1000L

        const val QUARANTINE_FIRST_BREACH_MS = 1 * 60 * 60 * 1000L
        const val QUARANTINE_REPEAT_BREACH_MS = 4 * 60 * 60 * 1000L
        const val REPEAT_BREACH_WINDOW_MS = 24 * 60 * 60 * 1000L

        // Bounds for the ONE tree walk we still do (on window-state / navigation changes).
        const val MAX_TRAVERSAL_DEPTH = 40
        const val MAX_NODES_PER_SCAN = 400

        val INCOGNITO_RESOURCE_ID_MARKERS = listOf(
            "incognito_toggle_button", "new_incognito_tab", "incognito_badge", "incognito"
        )
        val INCOGNITO_DESCRIPTION_MARKERS = listOf(
            "incognito", "private tab", "private browsing"
        )
    }

    private enum class BreachReason { NONE, EXPLICIT_CONTENT, INCOGNITO_MODE }

    // Word-boundary patterns so e.g. "assessment" or "popcorn" never match.
    private val breachPatterns = listOf(
        Pattern.compile("\\b(porn|xvideos|xnxx|pornhub|xhamster|redtube|erotic|nsfw)\\b", Pattern.CASE_INSENSITIVE)
    )

    private var lastSessionCheckTime = 0L
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // =================================================================================
    // Lifecycle
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

    /**
     * Recents/task-switcher hardening (Task 2). AndroidManifest marks MainActivity
     * android:excludeFromRecents="true", which already keeps the lockout/quarantine/
     * drift screens out of the Overview tray entirely — there's nothing there to swipe
     * away. This is the defensive backstop for onTaskRemoved(): a Service callback (NOT
     * an Activity one — Activity has no onTaskRemoved()) fired if this app's task is
     * ever removed from the recent-tasks list by any means. If that happens while an
     * enforcement state (quarantine, cool-off, or an unfinished Gate 1 read) is still
     * outstanding, we immediately re-present it rather than letting it be silently
     * abandoned in memory.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        reassertEnforcementIfNeeded()
    }

    private fun reassertEnforcementIfNeeded() {
        val prefs = prefs()
        val blocked = prefs.getStringSet(KEY_BLOCKED_SET, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        val now = System.currentTimeMillis()

        for (pkg in blocked) {
            val quarantineEnd = prefs.getLong(KEY_QUARANTINE_END_PREFIX + pkg, 0L)
            if (now < quarantineEnd) {
                launchQuarantineScreen(pkg, quarantineEnd)
                return
            }
        }
        for (pkg in blocked) {
            val coolOffEnd = prefs.getLong(KEY_DRIFT_COOLOFF_END_PREFIX + pkg, 0L)
            if (now < coolOffEnd) {
                launchDriftCoolOffScreen(pkg, coolOffEnd)
                return
            }
        }
        for (pkg in blocked) {
            val isAuthorized = prefs.getBoolean(KEY_SESSION_AUTHORIZED_PREFIX + pkg, false)
            val readingEnd = prefs.getLong(KEY_READING_END_PREFIX + pkg, 0L)
            if (!isAuthorized && now < readingEnd) {
                launchLockoutScreen(pkg)
                return
            }
        }
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

        // 1. Quarantine gate (content/incognito track). Cheap Long read — unthrottled.
        val quarantineEnd = prefs.getLong(KEY_QUARANTINE_END_PREFIX + packageName, 0L)
        if (currentTime < quarantineEnd) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchQuarantineScreen(packageName, quarantineEnd)
            return
        }

        // 2. Drift cool-off gate (overuse track). Also a cheap Long read, also
        // unthrottled, and deliberately a SEPARATE key/screen from quarantine above —
        // these two tracks must never be conflated.
        val coolOffEnd = prefs.getLong(KEY_DRIFT_COOLOFF_END_PREFIX + packageName, 0L)
        if (currentTime < coolOffEnd) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchDriftCoolOffScreen(packageName, coolOffEnd)
            return
        }

        // 3. Trapwire breach scan (explicit content OR incognito/private browsing).
        // Intentionally NOT throttled — this is the one check that must never lag — but
        // scoped so it stays cheap regardless of event frequency:
        //  - TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_TEXT_SELECTION_CHANGED: inspect exactly
        //    the node the user is typing into. No traversal at all — O(1).
        //  - TYPE_WINDOW_STATE_CHANGED: ONE bounded, editable-plus-incognito-marker
        //    sweep, checking both conditions in the same walk (Task 3's traversal
        //    optimization) rather than scanning the tree twice per navigation.
        //  - Everything else (notably TYPE_WINDOW_CONTENT_CHANGED, which fires on every
        //    scroll/DOM tick): skipped entirely.
        val reason = detectsBreach(event)
        if (reason != BreachReason.NONE) {
            if (reason == BreachReason.INCOGNITO_MODE) {
                Log.w(TAG, "Incognito/private-browsing indicator detected in $packageName")
            }
            triggerQuarantineBreach(packageName, currentTime)
            return
        }

        // 4. Session bookkeeping (idle timeout / progressive drift escalation).
        // Throttled — this is liveness tracking, not enforcement, so it's safe to coalesce.
        if ((currentTime - lastSessionCheckTime) < SESSION_THROTTLE_MS) return
        lastSessionCheckTime = currentTime
        handleSessionState(packageName, currentTime, prefs)
    }

    // =================================================================================
    // Session bookkeeping: idle timeout + Progressive Drift Escalation
    // =================================================================================

    private fun handleSessionState(packageName: String, currentTime: Long, prefs: SharedPreferences) {
        val lastActive = prefs.getLong(KEY_SESSION_LAST_ACTIVE_PREFIX + packageName, 0L)
        val sessionStart = prefs.getLong(KEY_SESSION_START_PREFIX + packageName, 0L)
        val isAuthorized = prefs.getBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)

        // Inactivity check: idle for > 10 minutes silently expires the session.
        if (isAuthorized && (currentTime - lastActive) > IDLE_TIMEOUT_MS) {
            deauthorize(packageName, prefs)
            launchLockoutScreen(packageName)
            return
        }

        if (!isAuthorized) {
            launchLockoutScreen(packageName)
            return
        }

        // Progressive Drift Escalation. session_start is intentionally NEVER reset just
        // because a checkpoint was cleared (that was the old bug — resetting it let a
        // user re-trigger and dismiss the 45-minute checkpoint forever, for unlimited
        // continuous access). It only resets on a brand-new authorized session or a
        // full deauthorization (idle timeout, cool-off, or quarantine). continuousDuration
        // therefore climbs monotonically through a single continuous-use streak, and
        // driftLevel — not session_start — is what remembers which checkpoints have
        // already been cleared in that streak.
        val continuousDuration = currentTime - sessionStart
        val driftLevel = prefs.getInt(KEY_DRIFT_LEVEL_PREFIX + packageName, 0)

        when {
            continuousDuration >= DRIFT_CHECKPOINT_3_MS && driftLevel < 3 -> {
                val coolOffEnd = currentTime + DRIFT_COOLOFF_DURATION_MS
                // commit(): this ends the session outright, same durability reasoning
                // as the quarantine write below.
                prefs.edit()
                    .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
                    .remove(KEY_SESSION_START_PREFIX + packageName)
                    .putInt(KEY_DRIFT_LEVEL_PREFIX + packageName, 0)
                    .putLong(KEY_DRIFT_COOLOFF_END_PREFIX + packageName, coolOffEnd)
                    .commit()
                performGlobalAction(GLOBAL_ACTION_HOME)
                launchDriftCoolOffScreen(packageName, coolOffEnd)
                return
            }
            continuousDuration >= DRIFT_CHECKPOINT_2_MS && driftLevel < 2 -> {
                launchDriftCheckpointScreen(packageName, level = 2)
                return
            }
            continuousDuration >= DRIFT_CHECKPOINT_1_MS && driftLevel < 1 -> {
                launchDriftCheckpointScreen(packageName, level = 1)
                return
            }
        }

        prefs.edit().putLong(KEY_SESSION_LAST_ACTIVE_PREFIX + packageName, currentTime).apply()
    }

    private fun deauthorize(packageName: String, prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
            .remove(KEY_SESSION_START_PREFIX + packageName)
            .putInt(KEY_DRIFT_LEVEL_PREFIX + packageName, 0)
            .apply()
    }

    // =================================================================================
    // Breach detection — explicit content (editable-only) + incognito indicators
    // =================================================================================

    private fun detectsBreach(event: AccessibilityEvent): BreachReason {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source
                try {
                    if (source != null && isLikelyInputField(source) && matchesTypedText(source)) {
                        BreachReason.EXPLICIT_CONTENT
                    } else {
                        BreachReason.NONE
                    }
                } finally {
                    recycleIfNeeded(source)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val root = rootInActiveWindow
                try {
                    scanWindowForBreach(root, NodeBudget(MAX_NODES_PER_SCAN))
                } finally {
                    recycleIfNeeded(root)
                }
            }
            else -> BreachReason.NONE
        }
    }

    /**
     * Bounded, depth- and count-limited walk that checks BOTH conditions in a single
     * pass (Task 3's traversal optimization — no reason to walk the tree twice per
     * navigation event). Recycles every child node it obtains once done with it.
     *
     * Explicit-content matching stays strictly scoped to editable nodes' TEXT (never
     * contentDescription — icon/button labels aren't something the user typed, and
     * scanning them was the original false-positive source). The incognito check is a
     * deliberate, narrow exception to that rule: it looks at a small, fixed set of known
     * resource-id substrings and content-description phrases for the incognito toggle/
     * badge specifically — not general page content — so it doesn't reopen the same
     * false-positive hole. Exact resource ids vary across Chrome versions and browser
     * forks, so this is best-effort; treat it as one layer, not a guarantee.
     */
    private fun scanWindowForBreach(node: AccessibilityNodeInfo?, budget: NodeBudget, depth: Int = 0): BreachReason {
        if (node == null || depth > MAX_TRAVERSAL_DEPTH || budget.remaining <= 0) return BreachReason.NONE
        budget.remaining--

        if (isIncognitoIndicator(node)) return BreachReason.INCOGNITO_MODE
        if (isLikelyInputField(node) && matchesTypedText(node)) return BreachReason.EXPLICIT_CONTENT

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = try {
                scanWindowForBreach(child, budget, depth + 1)
            } finally {
                recycleIfNeeded(child)
            }
            if (result != BreachReason.NONE) return result
        }
        return BreachReason.NONE
    }

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

    private fun isIncognitoIndicator(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName ?: ""
        if (INCOGNITO_RESOURCE_ID_MARKERS.any { resId.contains(it, ignoreCase = true) }) return true
        val description = node.contentDescription?.toString().orEmpty()
        return INCOGNITO_DESCRIPTION_MARKERS.any { description.contains(it, ignoreCase = true) }
    }

    private fun recycleIfNeeded(node: AccessibilityNodeInfo?) {
        // recycle() is a documented no-op from API 33 onward; on some OEM builds
        // double-recycling a cached node throws, so we gate it explicitly rather than
        // relying on the no-op behavior everywhere.
        if (node != null && Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private class NodeBudget(var remaining: Int)

    // =================================================================================
    // Quarantine enforcement (content/incognito track only — never fed by drift or
    // Home-Bypass state)
    // =================================================================================

    private fun triggerQuarantineBreach(packageName: String, currentTime: Long) {
        val prefs = prefs()
        val lastBreachTime = prefs.getLong(KEY_LAST_BREACH_PREFIX + packageName, 0L)
        val isRepeatBreach = (currentTime - lastBreachTime) < REPEAT_BREACH_WINDOW_MS

        val penaltyMillis = if (isRepeatBreach) QUARANTINE_REPEAT_BREACH_MS else QUARANTINE_FIRST_BREACH_MS
        val quarantineEnd = currentTime + penaltyMillis

        // commit() (synchronous) rather than apply() here deliberately: this is a write
        // that MUST survive an immediate process death — it's rare (at most once per
        // breach) so the small synchronous cost is a non-issue.
        prefs.edit()
            .putLong(KEY_QUARANTINE_END_PREFIX + packageName, quarantineEnd)
            .putLong(KEY_LAST_BREACH_PREFIX + packageName, currentTime)
            .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
            .remove(KEY_SESSION_START_PREFIX + packageName)
            .putInt(KEY_DRIFT_LEVEL_PREFIX + packageName, 0)
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

    private fun launchDriftCheckpointScreen(packageName: String, level: Int) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_DRIFT_CHECKPOINT)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_DRIFT_LEVEL, level)
        })
    }

    private fun launchDriftCoolOffScreen(packageName: String, coolOffEnd: Long) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_DRIFT_COOLOFF)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_COOLOFF_END, coolOffEnd)
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
const val EXTRA_DRIFT_LEVEL = "DRIFT_LEVEL"
const val EXTRA_COOLOFF_END = "COOLOFF_END"
const val MODE_GATE_ENTRY = "GATE_ENTRY"
const val MODE_DRIFT_CHECKPOINT = "DRIFT_CHECKPOINT"
const val MODE_DRIFT_COOLOFF = "DRIFT_COOLOFF"
const val MODE_QUARANTINE_HAMMER = "QUARANTINE_HAMMER"
const val MODE_DASHBOARD = "DASHBOARD"
