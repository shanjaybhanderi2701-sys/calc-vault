package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.settings.SettingsGraph

/**
 * Application entry point. Wires the encrypted credential store into [AuthGraph] and the
 * encrypted settings store into [SettingsGraph] so the calculator, onboarding, recovery,
 * fake-password, and settings view models can reach them. The wider DI graph and telemetry
 * attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthGraph.init(this)
        SettingsGraph.init(this)
    }
}
