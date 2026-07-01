package com.appblish.calculatorvault.applock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over AndroidX [BiometricPrompt] for the AppLock lock screen's Biometric
 * method. Device-credential fallback is intentionally not enabled — a system PIN/pattern is
 * not the vault secret — so only a real biometric (or the vault PIN keypad) unlocks.
 */
object BiometricAuth {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** Whether the device has an enrolled biometric we can prompt with. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        if (!canAuthenticate(activity)) {
            onError()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        onError()
                    }
                },
            )
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        prompt.authenticate(info)
    }
}
