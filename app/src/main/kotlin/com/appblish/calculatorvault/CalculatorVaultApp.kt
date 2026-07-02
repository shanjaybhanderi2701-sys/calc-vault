package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.applock.AppLockGraph
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.vault.VaultGraph

/**
 * Application entry point. Installs the device-backed service locators every surface shares:
 *  - [AuthGraph] — the encrypted credential store the calculator, onboarding, recovery,
 *    fake-password, and the AppLock lock screen resolve/verify PINs against.
 *  - [VaultGraph] — the keystore-backed crypto + encrypted, survive-uninstall vault content
 *    repository shared by every vault surface.
 *  - [AppLockGraph] — the AppLock + intruder-selfie stores (Phase 3).
 *
 * A fuller DI graph (Hilt) and telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthGraph.init(this)
        VaultGraph.init(this)
        AppLockGraph.init(this)
    }
}
