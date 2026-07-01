package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.applock.AppLockGraph
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.vault.VaultGraph

/**
 * Application entry point. Installs the device-backed graphs so every surface shares one
 * consistent, persistent store: the vault content repository (Phase 2), the credential store
 * the AppLock lock screen verifies against (Phase 1 auth spine), and the AppLock + intruder
 * stores (Phase 3). A fuller DI graph (Hilt) attaches here in the hardening phase.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VaultGraph.init(this)
        AuthGraph.init(this)
        AppLockGraph.init(this)
    }
}
