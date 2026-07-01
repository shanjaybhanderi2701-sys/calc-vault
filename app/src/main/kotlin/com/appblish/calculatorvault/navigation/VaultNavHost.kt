package com.appblish.calculatorvault.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.pin.PinEntryScreen
import com.appblish.calculatorvault.vault.VaultHomeScreen

/**
 * The whole app is three destinations:
 *   calculator (disguise) --secret code--> pin (gate) --auth--> vault home.
 * The back stack is deliberately shallow and the vault is popped off when the user
 * leaves, so returning to the app always lands back on the innocuous calculator.
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = VaultDestinations.CALCULATOR,
    ) {
        composable(VaultDestinations.CALCULATOR) {
            CalculatorScreen(
                onUnlock = { navController.navigate(VaultDestinations.PIN) },
            )
        }
        composable(VaultDestinations.PIN) {
            PinEntryScreen(
                onAuthenticated = {
                    navController.navigate(VaultDestinations.VAULT_HOME) {
                        // Drop the PIN screen so back from the vault returns to the calculator.
                        popUpTo(VaultDestinations.CALCULATOR)
                    }
                },
                onDismiss = { navController.popBackStack() },
            )
        }
        composable(VaultDestinations.VAULT_HOME) {
            VaultHomeScreen()
        }
    }
}
