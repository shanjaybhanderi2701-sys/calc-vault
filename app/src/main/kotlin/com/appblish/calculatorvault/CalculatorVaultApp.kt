package com.appblish.calculatorvault

import android.app.Application
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.vault.VaultGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Installs the device-backed vault graph (keystore-backed
 * crypto + encrypted content repository) and the [AuthGraph] credential store so the real
 * calculator PIN gate can resolve typed codes to the vault. A fuller DI graph and
 * telemetry attach here as those modules land.
 */
class CalculatorVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VaultGraph.init(this)
        AuthGraph.init(this)
        seedDebugPinIfNeeded()
    }

    /**
     * The standalone Phase-2 build has no onboarding UI yet — that flow (create/confirm
     * PIN + recovery) is Phase 1 (APP-158) and is merged into the disguise spine during
     * the Phase-6 integration (APP-163). Until then, seed the debug PIN `1234` on a fresh
     * install so the *real* calculator gate is exercisable for the Phase-2 demo instead of
     * a stub. Guarded by [BuildConfig.DEBUG] so a release build never ships a default PIN;
     * onboarding overwrites this seed once the flows are wired together.
     */
    private fun seedDebugPinIfNeeded() {
        if (!BuildConfig.DEBUG) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val store = AuthGraph.credentialStore
            if (!store.isOnboarded()) {
                store.setRealPin("1234")
                store.completeOnboarding()
            }
        }
    }
}
