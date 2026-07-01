package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.vault.VaultGraph

/**
 * Application entry point. Installs the device-backed vault graph (keystore-backed
 * crypto + encrypted content repository) so every vault surface shares one consistent,
 * persistent store. A fuller DI graph and telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VaultGraph.init(this)
    }
}
