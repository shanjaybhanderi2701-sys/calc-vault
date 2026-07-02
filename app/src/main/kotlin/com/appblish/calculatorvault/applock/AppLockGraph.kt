package com.appblish.calculatorvault.applock

import android.content.Context
import com.appblish.calculatorvault.intruder.EncryptedIntruderLogStore
import com.appblish.calculatorvault.intruder.InMemoryIntruderLogStore
import com.appblish.calculatorvault.intruder.IntruderLogStore

/**
 * Minimal service locator for the AppLock + Intruder singletons, mirroring
 * [com.appblish.calculatorvault.auth.AuthGraph] and
 * [com.appblish.calculatorvault.vault.VaultGraph]. One [AppLockStore] and one
 * [IntruderLogStore] are shared across the AppLock screen, the accessibility enforcement
 * service, and the lock-screen activity (which runs in-process but is entered from the
 * service, so it re-[init]s defensively). A real DI graph (Hilt) replaces this in hardening.
 *
 * If [init] was never called (previews, unit tests), the getters fall back to in-memory
 * stores so screens still render.
 */
object AppLockGraph {
    @Volatile
    private var lockStore: AppLockStore? = null

    @Volatile
    private var intruderStore: IntruderLogStore? = null

    /** Install the device-backed stores. Idempotent; safe from onCreate and the service. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (lockStore == null || intruderStore == null) {
            synchronized(this) {
                if (lockStore == null) lockStore = EncryptedAppLockStore(app)
                if (intruderStore == null) intruderStore = EncryptedIntruderLogStore(app)
            }
        }
    }

    /** Replace the stores (tests). */
    fun override(
        lock: AppLockStore,
        intruder: IntruderLogStore,
    ) {
        lockStore = lock
        intruderStore = intruder
    }

    val appLockStore: AppLockStore
        get() = lockStore ?: synchronized(this) { lockStore ?: InMemoryAppLockStore().also { lockStore = it } }

    val intruderLogStore: IntruderLogStore
        get() = intruderStore
            ?: synchronized(this) { intruderStore ?: InMemoryIntruderLogStore().also { intruderStore = it } }
}
