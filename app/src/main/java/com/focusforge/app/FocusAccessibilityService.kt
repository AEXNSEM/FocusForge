package com.focusforge.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class FocusAccessibilityService : AccessibilityService() {

    private val blockedPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (blockedPackages.contains(packageName)) {
                val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
                val unlockExpiry = prefs.getLong("unlock_expiry_${packageName}", 0L)
                val currentTime = System.currentTimeMillis()

                // If currently within a granted unlock pass, permit access
                if (currentTime < unlockExpiry) {
                    return
                }

                // Otherwise, intercept and launch the lockout screen
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
