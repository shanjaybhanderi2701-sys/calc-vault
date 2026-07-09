package com.appblish.calculatorvault.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

/**
 * Apply or clear `FLAG_SECURE` on the Activity hosting [context] **right now**, so the "Allow
 * screenshots" toggle (PIN Recovery W4) gives instant feedback without waiting for the next
 * `onResume` re-apply in [com.appblish.calculatorvault.MainActivity]. Turning screenshots on
 * clears the flag; turning it off restores it. No-op if no Activity is found (e.g. a preview).
 */
fun Context.applyWindowSecure(secure: Boolean) {
    val window = findActivity()?.window ?: return
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
