package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.vault.VaultGraph

/**
 * Application entry point. Wires the two service locators the app spine depends on:
 *  - [AuthGraph] — the encrypted credential store that the calculator, onboarding,
 *    recovery, and fake-password view models resolve PINs against.
 *  - [VaultGraph] — the device-backed vault graph (keystore-backed crypto + encrypted,
 *    survive-uninstall content repository) that every vault surface shares.
 *
 * A fuller DI graph (Hilt) and telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthGraph.init(this)
        VaultGraph.init(this)
    }
}
