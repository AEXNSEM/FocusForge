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

                // Active pass check
                if (currentTime < unlockExpiry) {
                    return
                }

                val lockIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("TRIGGERED_BY", packageName)
                }
                startActivity(lockIntent)
            }
        }
    }

    override fun onInterrupt() {}
}
