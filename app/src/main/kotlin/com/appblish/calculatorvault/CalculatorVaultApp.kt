package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.auth.AuthGraph

/**
 * Application entry point. Wires the encrypted credential store into [AuthGraph] so the
 * calculator, onboarding, recovery, and fake-password view models can reach it. The wider
 * DI graph and telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthGraph.init(this)
    }
}
