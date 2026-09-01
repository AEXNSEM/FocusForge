package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class FocusAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
            val blockedPackages = prefs.getStringSet("blocked_packages_set", setOf("com.android.chrome")) ?: emptySet()

            if (blockedPackages.contains(packageName)) {
                val unlockExpiry = prefs.getLong("unlock_expiry_${packageName}", 0L)
                val currentTime = System.currentTimeMillis()

                // Check if target is inside an authorized unlock window
                if (currentTime < unlockExpiry) {
                    return
                }

                // Check if an existing reading session is already pending
                val existingEndTime = prefs.getLong("reading_end_time_${packageName}", 0L)
                if (existingEndTime > currentTime) {
                    // Penalty surcharge: add 5 minutes (300,000 ms) to existing deadline
                    val penalizedEndTime = existingEndTime + (300 * 1000L)
                    prefs.edit()
                        .putLong("reading_end_time_${packageName}", penalizedEndTime)
                        .putBoolean("penalty_applied_${packageName}", true)
                        .apply()
                } else {
                    // First intercept: initialize standard 3-minute window (180,000 ms)
                    val initialEndTime = currentTime + (180 * 1000L)
                    prefs.edit()
                        .putLong("reading_end_time_${packageName}", initialEndTime)
                        .putBoolean("penalty_applied_${packageName}", false)
                        .apply()
                }

                val lockIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("TRIGGERED_BY", packageName)
                    putExtra("SESSION_TRIGGER_TIME", currentTime)
                }
                startActivity(lockIntent)
            }
        }
    }

    override fun onInterrupt() {}
}
