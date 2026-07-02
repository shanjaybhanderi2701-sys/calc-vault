package com.appblish.calculatorvault.applock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.text.TextUtils

/**
 * The two system permissions AppLock enforcement needs, and how to check / request them.
 * Following the flow-logic doc (§4, xlock sequence): **Usage Access → Accessibility**, one
 * plain-language primer per step, requested only when the user turns on AppLock — a denial
 * returns to the primer, never a dead end. No scare framing.
 *
 * - **Usage Access** (`PACKAGE_USAGE_STATS`) lets us read the foreground package as a
 *   reliable fallback / cross-check to the accessibility stream.
 * - **Accessibility** is the real-time trigger: the [AppLockAccessibilityService] observes
 *   window-state changes and raises the lock screen the instant a locked app comes forward.
 */
object AppLockPermissions {
    /** Whether the user has granted Usage Access to us. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode =
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Whether our [AppLockAccessibilityService] is enabled in system settings. */
    fun hasAccessibility(context: Context): Boolean {
        val expected = "${context.packageName}/${AppLockAccessibilityService::class.java.name}"
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** True once both permissions are granted — enforcement is fully live. */
    fun isEnforcementReady(context: Context): Boolean = hasUsageAccess(context) && hasAccessibility(context)

    /** System settings screen for Usage Access. */
    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** System settings screen for Accessibility services. */
    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Whether we can draw the lock screen over other apps (older devices / edge cases). */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** System settings screen to grant draw-over-other-apps for our package. */
    fun overlayIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
