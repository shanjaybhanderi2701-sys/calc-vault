package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.applock.AppLockGraph
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.settings.SettingsGraph
import com.appblish.calculatorvault.vault.VaultGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Installs the device-backed service locators every surface shares:
 *  - [AuthGraph] — the encrypted credential store the calculator, onboarding, recovery,
 *    fake-password, change-PIN, and the AppLock lock screen resolve/verify PINs against.
 *  - [VaultGraph] — the keystore-backed crypto + encrypted, survive-uninstall vault content
 *    repository shared by every vault surface.
 *  - [AppLockGraph] — the AppLock + intruder-selfie stores (Phase 3).
 *  - [SettingsGraph] — the encrypted settings store backing the Phase-5 settings surfaces.
 *
 * A fuller DI graph (Hilt) and telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthGraph.init(this)
        VaultGraph.init(this)
        AppLockGraph.init(this)
        SettingsGraph.init(this)
        // Warm the synchronous re-lock cache from persisted settings so VaultNavHost's
        // ON_STOP re-lock reflects the user's "Re-lock on background" choice (APP-205).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { SettingsGraph.warmCaches() }
    }
}
