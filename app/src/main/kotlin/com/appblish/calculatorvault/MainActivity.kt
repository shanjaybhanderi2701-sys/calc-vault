package com.appblish.calculatorvault

import android.os.Bundle
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
