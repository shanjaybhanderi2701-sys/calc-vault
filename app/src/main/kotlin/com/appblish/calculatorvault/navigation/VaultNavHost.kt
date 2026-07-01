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
import com.appblish.calculatorvault.settings.BackupScreen
import com.appblish.calculatorvault.settings.ChangePinScreen
import com.appblish.calculatorvault.settings.PermissionManagementScreen
import com.appblish.calculatorvault.settings.SettingsScreen
import com.appblish.calculatorvault.settings.ThemeScreen
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
                onOpenSettings =
                    if (kind is com.appblish.calculatorvault.auth.VaultKind.Real) {
                        { navController.navigate(VaultDestinations.SETTINGS) }
                    } else {
                        null
                    },
                onLock = { navController.popBackStack(VaultDestinations.CALCULATOR, inclusive = false) },
            )
        }

        composable(VaultDestinations.FAKE_PASSWORD) {
            FakePasswordScreen(onBack = { navController.popBackStack() })
        }

        // Settings surface (real vault only). The gear opens the root; each row navigates
        // to a dedicated sub-screen. Phase 5.
        composable(VaultDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onChangePin = { navController.navigate(VaultDestinations.SETTINGS_CHANGE_PIN) },
                onManageFakePasswords = { navController.navigate(VaultDestinations.FAKE_PASSWORD) },
                onTheme = { navController.navigate(VaultDestinations.SETTINGS_THEME) },
                onPermissions = { navController.navigate(VaultDestinations.SETTINGS_PERMISSIONS) },
                onBackup = { navController.navigate(VaultDestinations.SETTINGS_BACKUP) },
            )
        }

        composable(VaultDestinations.SETTINGS_CHANGE_PIN) {
            ChangePinScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(VaultDestinations.SETTINGS_THEME) {
            ThemeScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.SETTINGS_PERMISSIONS) {
            PermissionManagementScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.SETTINGS_BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
    }
}
