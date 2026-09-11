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

// =====================================================================================
// DEBUG_ACCELERATED_TIMERS
// Flip to false before shipping. When true, every user-facing threshold below is scaled
// down to a testable number of seconds instead of minutes/hours; when false, every
// threshold uses the exact production millisecond value. Declared as a bare top-level
// const in this file (not inside the class) so both FocusAccessibilityService.kt and
// MainActivity.kt can reference it unqualified — they're in the same package
// (com.focusforge.app), so no import is needed, the same way EXTRA_MODE and friends
// further down this file are already shared across both files.
// =====================================================================================
const val DEBUG_ACCELERATED_TIMERS = true

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
 *    private-browsing detection → an immediate 1hr/4hr lock, enforced by bringing
 *    MainActivity's quarantine overlay directly to the front (see Task C notes on
 *    launchQuarantineScreen — no GLOBAL_ACTION_HOME involved).
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
        const val KEY_LAST_BREACH_REASON_PREFIX = "last_breach_reason_"
        const val KEY_SESSION_AUTHORIZED_PREFIX = "session_authorized_"
        const val KEY_SESSION_START_PREFIX = "session_start_"
        const val KEY_SESSION_LAST_ACTIVE_PREFIX = "session_last_active_"
        const val KEY_DRIFT_LEVEL_PREFIX = "drift_level_"
        const val KEY_DRIFT_COOLOFF_END_PREFIX = "drift_cooloff_end_"
        const val KEY_READING_END_PREFIX = "reading_end_time_"

        val DEFAULT_BLOCKED = setOf("com.android.chrome")

        const val SESSION_THROTTLE_MS = 1_500L // internal polling cadence, not a feature timer — never scaled

        val IDLE_TIMEOUT_MS: Long =
            if (DEBUG_ACCELERATED_TIMERS) 15_000L else 10 * 60 * 1000L

        // Progressive Drift Escalation thresholds — cumulative continuous-usage duration
        // measured from a single, un-reset session_start (see handleSessionState()).
        // Note: these are `val`, not `const val` — a const val's initializer must be a
        // compile-time-constant literal, and Kotlin doesn't reliably treat an `if`
        // branching on another const as one across versions, so `val` is the safe form
        // here even though the value is still fixed at process start.
        val DRIFT_CHECKPOINT_1_MS: Long =                     // 30s Intent Anchor
            if (DEBUG_ACCELERATED_TIMERS) 30_000L else 45 * 60 * 1000L
        val DRIFT_CHECKPOINT_2_MS: Long =                     // 60s pause + re-verification quiz
            if (DEBUG_ACCELERATED_TIMERS) 60_000L else 90 * 60 * 1000L
        val DRIFT_CHECKPOINT_3_MS: Long =                     // mandatory cool-off
            if (DEBUG_ACCELERATED_TIMERS) 90_000L else 135 * 60 * 1000L
        val DRIFT_COOLOFF_DURATION_MS: Long =
            if (DEBUG_ACCELERATED_TIMERS) 20_000L else 15 * 60 * 1000L

        val QUARANTINE_FIRST_BREACH_MS: Long =
            if (DEBUG_ACCELERATED_TIMERS) 60_000L else 1 * 60 * 60 * 1000L
        val QUARANTINE_REPEAT_BREACH_MS: Long =
            if (DEBUG_ACCELERATED_TIMERS) 120_000L else 4 * 60 * 60 * 1000L

        // NOT scaled by design: this is the window that decides whether a second breach
        // counts as a "repeat" for escalation purposes. Testing 1hr→4hr escalation just
        // means breaching twice in quick succession — both breaches naturally fall
        // inside any 24-hour window, debug or not, so there's nothing to shrink here.
        const val REPEAT_BREACH_WINDOW_MS = 24 * 60 * 60 * 1000L

        // Bounds for the ONE tree walk we still do (on window-state / navigation changes).
        const val MAX_TRAVERSAL_DEPTH = 40
        const val MAX_NODES_PER_SCAN = 400

        val INCOGNITO_RESOURCE_ID_MARKERS = listOf(
            "incognito_toggle_button", "new_incognito_tab", "incognito_badge",
            "new_incognito_tab_menu_id", "incognito"
        )
        // Checked against a node's text AND contentDescription (a menu item like
        // "New Incognito Tab" exposes its label as `text`, not `contentDescription` —
        // checking only one was the gap that let a menu tap slip through undetected).
        val INCOGNITO_TEXT_MARKERS = listOf(
            "incognito", "private tab", "private browsing",
            "you've gone incognito", "close all incognito tabs", "new incognito tab"
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
                val reason = prefs.getString(KEY_LAST_BREACH_REASON_PREFIX + pkg, REASON_EXPLICIT_CONTENT)
                    ?: REASON_EXPLICIT_CONTENT
                launchQuarantineScreen(pkg, quarantineEnd, reason)
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
        // Task C: no GLOBAL_ACTION_HOME here — sending the user home first, then racing
        // to bring MainActivity to front, was the unreliable part (the home-screen
        // transition sometimes "won" the race, leaving the user staring at the
        // launcher instead of the overlay). Launching MainActivity directly with
        // REORDER_TO_FRONT is both the fix and one less step.
        val quarantineEnd = prefs.getLong(KEY_QUARANTINE_END_PREFIX + packageName, 0L)
        if (currentTime < quarantineEnd) {
            launchQuarantineScreen(packageName, quarantineEnd, REASON_EXPLICIT_CONTENT)
            return
        }

        // 2. Drift cool-off gate (overuse track). Same reasoning as above — direct
        // launch, no GLOBAL_ACTION_HOME. Also a cheap Long read, also unthrottled, and
        // deliberately a SEPARATE key/screen from quarantine above — these two tracks
        // must never be conflated.
        val coolOffEnd = prefs.getLong(KEY_DRIFT_COOLOFF_END_PREFIX + packageName, 0L)
        if (currentTime < coolOffEnd) {
            launchDriftCoolOffScreen(packageName, coolOffEnd)
            return
        }

        // 3. Trapwire breach scan (explicit content OR incognito/private browsing).
        // Intentionally NOT throttled — this is the one check that must never lag — but
        // scoped so it stays cheap regardless of event frequency:
        //  - TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_TEXT_SELECTION_CHANGED: inspect exactly
        //    the node the user is typing into. No traversal at all — O(1).
        //  - TYPE_VIEW_CLICKED / TYPE_VIEW_FOCUSED: inspect exactly the tapped/focused
        //    node for an incognito marker. Also O(1).
        //  - TYPE_NOTIFICATION_STATE_CHANGED: inspect the notification's own text. O(1).
        //  - TYPE_WINDOW_STATE_CHANGED: ONE bounded, editable-plus-incognito-marker
        //    sweep, checking both conditions in the same walk (Task 3's traversal
        //    optimization) rather than scanning the tree twice per navigation.
        //  - Everything else (notably TYPE_WINDOW_CONTENT_CHANGED, which fires on every
        //    scroll/DOM tick): skipped entirely.
        // This runs completely unconditionally on isAuthorized — an unauthorized/Gate-1
        // session is inspected exactly the same as an authorized one, so a breach here
        // is caught before any Gate 1 routing happens, not after.
        val reason = detectsBreach(event)
        if (reason != BreachReason.NONE) {
            triggerQuarantineBreach(packageName, currentTime, reason)
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
            // Closes the actual gap that let "type explicit term inside Incognito" slip
            // through: tapping "New Incognito Tab" from Chrome's overflow menu doesn't
            // reliably produce a TYPE_WINDOW_STATE_CHANGED event before the next window-
            // state event routes an unauthorized session to Gate 1 — by the time Chrome's
            // incognito UI has actually rendered and would show up in a window sweep,
            // Gate 1 may already have foreground focus, and Chrome (now backgrounded)
            // stops producing window-state events for us to catch it on. Watching the
            // click/focus on the menu item itself catches the intent immediately,
            // before that race window opens. Deliberately scoped to incognito only
            // (not general explicit-content matching) — a bare click/focus doesn't carry
            // meaningful typed content the way TYPE_VIEW_TEXT_CHANGED does.
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                try {
                    if (source != null && isIncognitoIndicator(source)) {
                        BreachReason.INCOGNITO_MODE
                    } else {
                        BreachReason.NONE
                    }
                } finally {
                    recycleIfNeeded(source)
                }
            }
            // Chrome's persistent "N Incognito tabs — tap to close" notification carries
            // its text directly on the AccessibilityEvent (no node tree involved), so
            // this is as cheap as the click/focus path — a plain string check, no walk.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notificationText = event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
                if (INCOGNITO_TEXT_MARKERS.any { notificationText.contains(it, ignoreCase = true) }) {
                    BreachReason.INCOGNITO_MODE
                } else {
                    BreachReason.NONE
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
        val text = node.text?.toString().orEmpty()
        return INCOGNITO_TEXT_MARKERS.any { marker ->
            description.contains(marker, ignoreCase = true) || text.contains(marker, ignoreCase = true)
        }
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

    private fun triggerQuarantineBreach(packageName: String, currentTime: Long, reason: BreachReason) {
        if (reason == BreachReason.INCOGNITO_MODE) {
            Log.w(TAG, "Incognito/private-browsing indicator detected in $packageName")
        }

        val prefs = prefs()
        val lastBreachTime = prefs.getLong(KEY_LAST_BREACH_PREFIX + packageName, 0L)
        val isRepeatBreach = (currentTime - lastBreachTime) < REPEAT_BREACH_WINDOW_MS

        val penaltyMillis = if (isRepeatBreach) QUARANTINE_REPEAT_BREACH_MS else QUARANTINE_FIRST_BREACH_MS
        val quarantineEnd = currentTime + penaltyMillis
        val reasonExtra = if (reason == BreachReason.INCOGNITO_MODE) REASON_INCOGNITO else REASON_EXPLICIT_CONTENT

        // commit() (synchronous) rather than apply() here deliberately: this is a write
        // that MUST survive an immediate process death — it's rare (at most once per
        // breach) so the small synchronous cost is a non-issue.
        prefs.edit()
            .putLong(KEY_QUARANTINE_END_PREFIX + packageName, quarantineEnd)
            .putLong(KEY_LAST_BREACH_PREFIX + packageName, currentTime)
            .putString(KEY_LAST_BREACH_REASON_PREFIX + packageName, reasonExtra)
            .putBoolean(KEY_SESSION_AUTHORIZED_PREFIX + packageName, false)
            .remove(KEY_SESSION_START_PREFIX + packageName)
            .putInt(KEY_DRIFT_LEVEL_PREFIX + packageName, 0)
            .commit()

        // Task C: no GLOBAL_ACTION_HOME — direct launch is the reliable path.
        launchQuarantineScreen(packageName, quarantineEnd, reasonExtra)
    }

    // =================================================================================
    // Screen launches — Task C: FLAG_ACTIVITY_REORDER_TO_FRONT instead of SINGLE_TOP.
    // MainActivity already declares android:launchMode="singleTop" in the manifest, so
    // dropping the Intent-level SINGLE_TOP flag changes nothing about onNewIntent()
    // delivery (that's driven by the manifest launch mode, not this flag) — adding
    // REORDER_TO_FRONT is what actually helps here, ensuring the activity is brought to
    // the front of its task instead of relying on a preceding GLOBAL_ACTION_HOME to
    // clear the way (removed — see onAccessibilityEvent / triggerQuarantineBreach /
    // handleSessionState). This relies on the same startActivity-from-a-bound-
    // accessibility-service exemption every one of these calls already depended on
    // before this change — nothing new is being asked of the OS here, only the extra
    // HOME step is gone.
    // =================================================================================

    private fun launchLockoutScreen(packageName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_MODE, MODE_GATE_ENTRY)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
        })
    }

    private fun launchDriftCheckpointScreen(packageName: String, level: Int) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_MODE, MODE_DRIFT_CHECKPOINT)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_DRIFT_LEVEL, level)
        })
    }

    private fun launchDriftCoolOffScreen(packageName: String, coolOffEnd: Long) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_MODE, MODE_DRIFT_COOLOFF)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_COOLOFF_END, coolOffEnd)
        })
    }

    private fun launchQuarantineScreen(packageName: String, quarantineEnd: Long, reason: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_MODE, MODE_QUARANTINE_HAMMER)
            putExtra(EXTRA_TRIGGERED_BY, packageName)
            putExtra(EXTRA_QUARANTINE_END, quarantineEnd)
            putExtra(EXTRA_BREACH_REASON, reason)
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
const val EXTRA_BREACH_REASON = "BREACH_REASON"
const val MODE_GATE_ENTRY = "GATE_ENTRY"
const val MODE_DRIFT_CHECKPOINT = "DRIFT_CHECKPOINT"
const val MODE_DRIFT_COOLOFF = "DRIFT_COOLOFF"
const val MODE_QUARANTINE_HAMMER = "QUARANTINE_HAMMER"
const val MODE_DASHBOARD = "DASHBOARD"
const val REASON_EXPLICIT_CONTENT = "EXPLICIT_CONTENT"
const val REASON_INCOGNITO = "INCOGNITO_MODE"
