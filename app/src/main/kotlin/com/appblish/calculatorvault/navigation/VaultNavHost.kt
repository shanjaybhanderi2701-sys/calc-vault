package com.appblish.calculatorvault.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.fakepassword.FakePasswordScreen
import com.appblish.calculatorvault.onboarding.OnboardingRoute
import com.appblish.calculatorvault.recovery.ForgotPasswordRoute
import com.appblish.calculatorvault.vault.UnlockedVaultScreen

/**
 * The Phase 1 auth spine. Starts on the [VaultDestinations.GATE] splash, which routes to
 * onboarding on first run or straight to the calculator disguise for a returning user. The
 * disguise is the only thing an onlooker sees; a resolved PIN opens the matching vault
 * (real or a decoy), and the back stack stays shallow so leaving the vault always lands
 * back on the calculator.
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = VaultDestinations.GATE,
    ) {
        composable(VaultDestinations.GATE) {
            GateScreen(
                onOnboard = {
                    navController.navigate(VaultDestinations.ONBOARDING) {
                        popUpTo(VaultDestinations.GATE) { inclusive = true }
                    }
                },
                onReady = {
                    navController.navigate(VaultDestinations.CALCULATOR) {
                        popUpTo(VaultDestinations.GATE) { inclusive = true }
                    }
                },
            )
        }

        composable(VaultDestinations.ONBOARDING) {
            OnboardingRoute(
                onComplete = {
                    navController.navigate(VaultDestinations.CALCULATOR) {
                        popUpTo(VaultDestinations.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(VaultDestinations.CALCULATOR) {
            CalculatorScreen(
                onUnlock = { kind -> navController.navigate(VaultDestinations.unlockedRoute(kind)) },
                onForgotPin = { navController.navigate(VaultDestinations.FORGOT_PASSWORD) },
            )
        }

        composable(VaultDestinations.FORGOT_PASSWORD) {
            ForgotPasswordRoute(
                onReset = { navController.popBackStack(VaultDestinations.CALCULATOR, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = VaultDestinations.UNLOCKED_ROUTE,
            arguments = listOf(navArgument(VaultDestinations.UNLOCKED_ARG) { type = NavType.StringType }),
        ) { entry ->
            val kind = VaultDestinations.parseKind(entry.arguments?.getString(VaultDestinations.UNLOCKED_ARG))
            UnlockedVaultScreen(
                kind = kind,
                onManageFakePasswords = { navController.navigate(VaultDestinations.FAKE_PASSWORD) },
                onLock = { navController.popBackStack(VaultDestinations.CALCULATOR, inclusive = false) },
            )
        }

        composable(VaultDestinations.FAKE_PASSWORD) {
            FakePasswordScreen(onBack = { navController.popBackStack() })
        }
    }
}
