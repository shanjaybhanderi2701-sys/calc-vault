package com.appblish.calculatorvault.applock

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.appblish.calculatorvault.applock.ui.LockScreenContent
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.intruder.IntruderCamera
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The full-screen PIN challenge raised by [AppLockAccessibilityService] when a locked app
 * comes to the foreground. Verifies the typed secret against the vault's
 * [com.appblish.calculatorvault.auth.CredentialStore] (the same PIN that opens the vault);
 * on success it records the unlock (starting the re-lock grace window) and finishes,
 * revealing the app behind it. On repeated wrong entries it fires the Intruder Selfie at the
 * configured threshold and logs it with the target app's badge.
 *
 * Back is intercepted: you cannot dismiss the lock to reach the app — back sends you home.
 */
class LockScreenActivity : FragmentActivity() {
    private lateinit var targetPackage: String
    private var appLabel: String = ""
    private var appIcon: ImageBitmap? = null

    private val inventory by lazy { AppInventory(applicationContext) }
    private val camera by lazy { IntruderCamera(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthGraph.init(applicationContext)
        AppLockGraph.init(applicationContext)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        resolveTargetApp()

        // Back must not reveal the locked app; drop the user to the home screen instead.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goHome()
                }
            },
        )

        var pin by mutableStateOf("")
        var error by mutableStateOf(false)
        var wrongAttempts by mutableIntStateOf(0)
        var method by mutableStateOf(LockMethod.Pin)

        // Load the persisted lock method + prime biometric.
        lifecycleScope.launch {
            method = AppLockGraph.appLockStore.settings().lockMethod
            if (method == LockMethod.Biometric) promptBiometric(::onUnlocked)
        }

        setContent {
            CalculatorVaultTheme {
                LockScreenContent(
                    appLabel = appLabel,
                    appIcon = appIcon,
                    method = method,
                    pinLength = pin.length,
                    maxPinLength = PIN_LENGTH,
                    error = error,
                    biometricAvailable = BiometricAuth.canAuthenticate(this),
                    onDigit = { ch ->
                        if (pin.length < PIN_LENGTH) {
                            error = false
                            pin += ch
                            if (pin.length == PIN_LENGTH) {
                                val entered = pin
                                pin = ""
                                lifecycleScope.launch {
                                    if (verify(entered)) {
                                        onUnlocked()
                                    } else {
                                        error = true
                                        wrongAttempts += 1
                                        onWrongAttempt(wrongAttempts)
                                    }
                                }
                            }
                        }
                    },
                    onBackspace = {
                        error = false
                        if (pin.isNotEmpty()) pin = pin.dropLast(1)
                    },
                    onBiometric = { promptBiometric(::onUnlocked) },
                    onChangeMethod = {
                        method =
                            when (method) {
                                LockMethod.Pin -> LockMethod.Pattern
                                LockMethod.Pattern -> LockMethod.Passcode
                                LockMethod.Passcode -> LockMethod.Biometric
                                LockMethod.Biometric -> LockMethod.Pin
                            }
                        lifecycleScope.launch {
                            val store = AppLockGraph.appLockStore
                            store.setSettings(store.settings().copy(lockMethod = method))
                        }
                    },
                )
            }
        }
    }

    private fun resolveTargetApp() {
        val pm = packageManager
        val info: ApplicationInfo? = runCatching { pm.getApplicationInfo(targetPackage, 0) }.getOrNull()
        appLabel = info?.let { pm.getApplicationLabel(it).toString() } ?: targetPackage
        lifecycleScope.launch { appIcon = inventory.icon(targetPackage) }
    }

    /** Verify against the vault secret; a debug build with no PIN set accepts [DEBUG_PIN]. */
    private suspend fun verify(entered: String): Boolean {
        val store = AuthGraph.credentialStore
        if (store.resolve(entered) != null) return true
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return debuggable && !store.isOnboarded() && entered == DEBUG_PIN
    }

    private fun onUnlocked() {
        val now = System.currentTimeMillis()
        AppLockSession.shared.markUnlocked(targetPackage, now)
        lifecycleScope.launch {
            AppLockGraph.appLockStore.recordUnlock(targetPackage, now)
        }
        finish()
    }

    private fun onWrongAttempt(attempts: Int) {
        lifecycleScope.launch {
            val settings = AppLockGraph.appLockStore.settings()
            if (!AppLockLogic.shouldCaptureIntruder(attempts, settings)) return@launch
            val photo = runCatching { camera.captureSelfie(this@LockScreenActivity) }.getOrNull()
            AppLockGraph.intruderLogStore.record(
                id = UUID.randomUUID().toString(),
                packageName = targetPackage,
                appLabel = appLabel,
                timestampMs = System.currentTimeMillis(),
                photoBytes = photo,
            )
        }
    }

    private fun promptBiometric(onSuccess: () -> Unit) {
        BiometricAuth.prompt(
            activity = this,
            title = "Unlock $appLabel",
            subtitle = "Confirm it's you",
            onSuccess = onSuccess,
            onError = { /* fall back to the keypad */ },
        )
    }

    private fun goHome() {
        val home =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        runCatching { startActivity(home) }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_locked_package"
        private const val PIN_LENGTH = 4
        private const val DEBUG_PIN = "1234"
    }
}
