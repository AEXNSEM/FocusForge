package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class FocusAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            
            val packageName = event.packageName?.toString() ?: return

            // Don't intercept FocusForge itself
            if (packageName == this.packageName) return

            val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
            val blockedPackages = prefs.getStringSet("blocked_packages_set", setOf("com.android.chrome")) ?: emptySet()

            if (blockedPackages.contains(packageName)) {
                val currentTime = System.currentTimeMillis()
                val unlockExpiry = prefs.getLong("unlock_expiry_${packageName}", 0L)

                // 1. If currently inside a valid granted window, let user pass
                if (currentTime < unlockExpiry) {
                    return
                }

                // 2. Read existing timer state
                val existingEndTime = prefs.getLong("reading_end_time_${packageName}", 0L)

                val targetEndTime = if (existingEndTime > currentTime) {
                    // Repeat attempt detected while active lockout pending -> add 5 min penalty
                    val penalized = existingEndTime + (300 * 1000L)
                    prefs.edit()
                        .putLong("reading_end_time_${packageName}", penalized)
                        .putBoolean("penalty_applied_${packageName}", true)
                        .apply()
                    penalized
                } else {
                    // Fresh lockout -> set standard 3-min window (180s)
                    val initial = currentTime + (180 * 1000L)
                    prefs.edit()
                        .putLong("reading_end_time_${packageName}", initial)
                        .putBoolean("penalty_applied_${packageName}", false)
                        .apply()
                    initial
                }

                // 3. Launch or bring FocusForge to the front with explicit fresh flags
                val lockIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("TRIGGERED_BY", packageName)
                    putExtra("TARGET_END_TIME", targetEndTime)
                    putExtra("TRIGGER_STAMP", currentTime)
                }
                startActivity(lockIntent)
            }
        }
    }

    override fun onInterrupt() {}
}
