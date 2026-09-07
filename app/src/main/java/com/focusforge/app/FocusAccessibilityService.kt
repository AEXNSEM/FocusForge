package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class FocusAccessibilityService : AccessibilityService() {

    private var lastEventTime = 0L

    // Word boundary regex patterns to prevent false positives (e.g., 'assessment' won't match)
    private val breachPatterns = listOf(
        Pattern.compile("\\b(porn|xvideos|xnxx|pornhub|xhamster|redtube|erotic|nsfw)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(incognito|private browsing)\\b", Pattern.CASE_INSENSITIVE)
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val currentTime = System.currentTimeMillis()
        val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
        val blockedPackages = prefs.getStringSet("blocked_packages_set", setOf("com.android.chrome")) ?: emptySet()

        // 1. Check Global Quarantine Status for blocked targets
        if (blockedPackages.contains(packageName)) {
            val quarantineEnd = prefs.getLong("quarantine_end_${packageName}", 0L)
            if (currentTime < quarantineEnd) {
                // Instantly bounce user back to home
                performGlobalAction(GLOBAL_ACTION_HOME)
                launchQuarantineScreen(packageName, quarantineEnd)
                return
            }
        }

        // 2. Real-time Content Sniffing on Active Target Apps
        if (blockedPackages.contains(packageName)) {
            if (inspectNodeForBreach(rootInActiveWindow)) {
                triggerQuarantineBreach(packageName, currentTime)
                return
            }
        }

        // Throttle window transitions
        if ((currentTime - lastEventTime) < 1500L) return

        // 3. Session Management (Idle reset & 45-min drift check)
        if (blockedPackages.contains(packageName)) {
            lastEventTime = currentTime
            val lastActive = prefs.getLong("session_last_active_${packageName}", 0L)
            val sessionStart = prefs.getLong("session_start_${packageName}", 0L)
            val isAuthorized = prefs.getBoolean("session_authorized_${packageName}", false)

            // Inactivity Check: If left idle for > 10 minutes (600,000 ms), terminate session
            if (isAuthorized && (currentTime - lastActive) > (10 * 60 * 1000L)) {
                prefs.edit()
                    .putBoolean("session_authorized_${packageName}", false)
                    .remove("session_start_${packageName}")
                    .remove("checkpoint_dismissed_${packageName}")
                    .apply()
                launchLockoutScreen(packageName)
                return
            }

            // Not yet authorized via 3-min cooldown + quiz
            if (!isAuthorized) {
                launchLockoutScreen(packageName)
                return
            }

            // 45-Minute Continuous Drift Check
            val continuousDuration = currentTime - sessionStart
            val checkpointCleared = prefs.getBoolean("checkpoint_dismissed_${packageName}", false)
            if (continuousDuration >= (45 * 60 * 1000L) && !checkpointCleared) {
                launchDriftCheckpointScreen(packageName)
                return
            }

            // Valid active session -> update last active timestamp
            prefs.edit().putLong("session_last_active_${packageName}", currentTime).apply()
        }
    }

    private fun inspectNodeForBreach(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val fullContent = "$text $contentDesc"

        if (fullContent.isNotBlank()) {
            for (pattern in breachPatterns) {
                if (pattern.matcher(fullContent).find()) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (inspectNodeForBreach(child)) return true
        }
        return false
    }

    private fun triggerQuarantineBreach(packageName: String, currentTime: Long) {
        val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
        val lastBreachTime = prefs.getLong("last_breach_timestamp_${packageName}", 0L)
        val isRepeatBreach = (currentTime - lastBreachTime) < (24 * 60 * 60 * 1000L)

        // 1st breach: 1 hour (3600s); Repeat breach within 24h: 4 hours (14400s)
        val penaltyMillis = if (isRepeatBreach) 4 * 60 * 60 * 1000L else 1 * 60 * 60 * 1000L
        val quarantineEnd = currentTime + penaltyMillis

        prefs.edit()
            .putLong("quarantine_end_${packageName}", quarantineEnd)
            .putLong("last_breach_timestamp_${packageName}", currentTime)
            .putBoolean("session_authorized_${packageName}", false)
            .remove("session_start_${packageName}")
            .remove("checkpoint_dismissed_${packageName}")
            .apply()

        performGlobalAction(GLOBAL_ACTION_HOME)
        launchQuarantineScreen(packageName, quarantineEnd)
    }

    private fun launchLockoutScreen(packageName: String) {
        val lockIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("MODE", "GATE_ENTRY")
            putExtra("TRIGGERED_BY", packageName)
        }
        startActivity(lockIntent)
    }

    private fun launchDriftCheckpointScreen(packageName: String) {
        val checkpointIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("MODE", "DRIFT_CHECKPOINT")
            putExtra("TRIGGERED_BY", packageName)
        }
        startActivity(checkpointIntent)
    }

    private fun launchQuarantineScreen(packageName: String, quarantineEnd: Long) {
        val quarantineIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("MODE", "QUARANTINE_HAMMER")
            putExtra("TRIGGERED_BY", packageName)
            putExtra("QUARANTINE_END", quarantineEnd)
        }
        startActivity(quarantineIntent)
    }

    override fun onInterrupt() {}
}
