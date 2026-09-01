package com.focusforge.app

import android.accessibilityservice.AccessibilityService
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
                val lockIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("TRIGGERED_BY", packageName)
                }
                startActivity(lockIntent)
            }
        }
    }

    override fun onInterrupt() {}
}
