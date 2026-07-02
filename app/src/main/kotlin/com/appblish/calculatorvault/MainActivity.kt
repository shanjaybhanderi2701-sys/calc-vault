package com.appblish.calculatorvault

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.navigation.VaultNavHost
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme

/**
 * Single-activity host. Owns nothing but the Compose tree; navigation between the
 * calculator disguise, PIN entry, and the vault lives in [VaultNavHost].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Privacy hardening (APP-205, match reference app xlock): mark the window secure so no
        // screenshot / screen-record can capture vault content and the Recents thumbnail is
        // blanked, and disable the recents snapshot outright on API 33+. Paired with
        // excludeFromRecents in the manifest and the ProcessLifecycleOwner re-lock in
        // VaultNavHost, an unlocked vault can never be observed from outside the live session.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        enableEdgeToEdge()
        setContent {
            CalculatorVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VaultNavHost()
                }
            }
        }
    }
}
