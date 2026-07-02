package com.appblish.calculatorvault.applock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The real-time enforcement engine. Android surfaces every foreground app change as a
 * `TYPE_WINDOW_STATE_CHANGED` accessibility event; for each, we ask [AppLockSession] whether
 * the incoming package is locked and outside its unlock/grace window and, if so, raise the
 * full-screen [LockScreenActivity] before the app's content is usable.
 *
 * The locked set and settings are cached in memory (refreshed off the event thread) so the
 * accessibility callback stays cheap — it must never touch encrypted prefs or block. This is
 * the "lock enforcement (Usage Access + Accessibility)" half of the phase; the PIN challenge
 * and intruder capture live in [LockScreenActivity].
 */
class AppLockAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lockedCache: Set<String> = emptySet()

    @Volatile
    private var relockDelayMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLockGraph.init(applicationContext)
        // Periodically mirror the encrypted store into cheap in-memory caches. Lock changes
        // are rare, so a light poll keeps the hot event path allocation- and IO-free.
        scope.launch {
            val store = AppLockGraph.appLockStore
            while (isActive) {
                lockedCache = store.lockedPackages()
                relockDelayMs = store.settings().relockDelayMs
                delay(REFRESH_MS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val locked = pkg in lockedCache
        val shouldPrompt =
            AppLockSession.shared.onForeground(
                packageName = pkg,
                isLocked = locked,
                ownPackage = packageName,
                relockDelayMs = relockDelayMs,
                now = System.currentTimeMillis(),
            )
        if (shouldPrompt) launchLockScreen(pkg)
    }

    private fun launchLockScreen(targetPackage: String) {
        val intent =
            Intent(this, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(LockScreenActivity.EXTRA_PACKAGE, targetPackage)
            }
        runCatching { startActivity(intent) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val REFRESH_MS = 1_500L
    }
}
