package com.appblish.calculatorvault.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.fakepassword.FakePasswordScreen
import com.appblish.calculatorvault.onboarding.OnboardingRoute
import com.appblish.calculatorvault.recovery.ForgotPasswordRoute
import com.appblish.calculatorvault.vault.CategoryScreen
import com.appblish.calculatorvault.vault.CategoryViewModel
import com.appblish.calculatorvault.vault.HideImportScreen
import com.appblish.calculatorvault.vault.HideImportViewModel
import com.appblish.calculatorvault.vault.RecycleBinScreen
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.VaultShellScreen
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.ui.StoragePermissionScreen
import com.appblish.calculatorvault.vault.viewer.FolderSlideshowScreen
import com.appblish.calculatorvault.vault.viewer.ItemViewerScreen
import com.appblish.calculatorvault.vault.viewer.SlideshowViewModel
import com.appblish.calculatorvault.vault.viewer.ViewerViewModel

/**
 * The unified app spine (Phase 6). Starts on the [VaultDestinations.GATE] splash, which
 * routes to onboarding on first run or straight to the calculator disguise for a returning
 * user. The disguise is the only thing an onlooker sees; typing a configured code on the
 * calculator resolves the vault it opens (real or a decoy) and its passphrase, and — after
 * the point-of-need All Files Access primer if needed — lands on the Phase-2 vault shell.
 * Inside the vault the shell pushes category → hide/import, viewers, folder slideshow, and
 * the recycle bin. The hidden long-press recovery gesture opens forgot-password.
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext

    // Open the encrypted public-storage vault for the current session and land on the
    // shell, dropping the disguise spine so back returns to the calculator. unlock() derives
    // the data key from the session passphrase and loads the namespaced .CalcVault/ index;
    // it is a safe no-op if either the passphrase or All Files Access is still missing.
    fun enterVault() {
        VaultGraph.contentRepository.unlock()
        navController.navigate(VaultDestinations.VAULT_SHELL) {
            popUpTo(VaultDestinations.CALCULATOR)
            launchSingleTop = true
        }
    }

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
                // A matched code both identifies the vault (real/decoy → storage namespace)
                // and is the passphrase that derives its data key. Re-key the shared
                // repository for this session first so a previous session's content can
                // never leak across the calculator boundary (decoy isolation), then open the
                // vault directly if All Files Access is granted, else show the primer.
                onUnlock = { kind, code ->
                    VaultGraph.contentRepository.lock()
                    VaultSession.begin(code, VaultDestinations.storageId(kind))
                    if (StoragePermissions.hasAllFilesAccess(context)) {
                        enterVault()
                    } else {
                        navController.navigate(VaultDestinations.STORAGE_PRIMER) {
                            // Drop the calculator so back from the primer stays on the calculator.
                            popUpTo(VaultDestinations.CALCULATOR)
                        }
                    }
                },
                onForgotPin = { navController.navigate(VaultDestinations.FORGOT_PASSWORD) },
            )
        }

        composable(VaultDestinations.FORGOT_PASSWORD) {
            ForgotPasswordRoute(
                onReset = { navController.popBackStack(VaultDestinations.CALCULATOR, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(VaultDestinations.STORAGE_PRIMER) {
            StoragePermissionScreen(
                onGranted = { enterVault() },
                onBack = { navController.popBackStack() },
            )
        }

        composable(VaultDestinations.VAULT_SHELL) {
            VaultShellScreen(
                onCategoryClick = { navController.navigate(VaultDestinations.category(it)) },
                onRecentClick = { navController.navigate(VaultDestinations.viewer(it.id)) },
                onRecycleBinClick = { navController.navigate(VaultDestinations.RECYCLE_BIN) },
                onDisguiseClick = { navController.navigate(VaultDestinations.FAKE_PASSWORD) },
                onSettingsClick = { /* Settings — wired to the Phase-5 screen during that merge. */ },
            )
        }

        composable(
            route = VaultDestinations.CATEGORY,
            arguments = listOf(navArgument(VaultDestinations.ARG_CATEGORY) { type = NavType.StringType }),
        ) { entry ->
            val category = entry.category()
            val vm: CategoryViewModel =
                viewModel(
                    key = "category-${category.name}",
                    factory = viewModelFactory { initializer { CategoryViewModel(category) } },
                )
            CategoryScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenItem = { navController.navigate(VaultDestinations.viewer(it.id)) },
                onHide = { navController.navigate(VaultDestinations.hide(category)) },
            )
        }

        composable(
            route = VaultDestinations.HIDE,
            arguments = listOf(navArgument(VaultDestinations.ARG_CATEGORY) { type = NavType.StringType }),
        ) { entry ->
            val category = entry.category()
            val hideContext = LocalContext.current.applicationContext
            val vm: HideImportViewModel =
                viewModel(
                    key = "hide-${category.name}",
                    factory =
                        viewModelFactory {
                            initializer { HideImportViewModel(category, mediaSource = MediaSource(hideContext)) }
                        },
                )
            HideImportScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onHidden = { navController.popBackStack() },
            )
        }

        composable(
            route = VaultDestinations.VIEWER,
            arguments = listOf(navArgument(VaultDestinations.ARG_ITEM_ID) { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString(VaultDestinations.ARG_ITEM_ID).orEmpty()
            val vm: ViewerViewModel =
                viewModel(
                    key = "viewer-$itemId",
                    factory = viewModelFactory { initializer { ViewerViewModel(itemId) } },
                )
            val item by vm.item.collectAsStateWithLifecycle()
            val bytes by vm.decrypted.collectAsStateWithLifecycle()
            item?.let { current ->
                ItemViewerScreen(
                    item = current,
                    bytes = bytes,
                    onBack = { navController.popBackStack() },
                    onDelete = {
                        vm.delete()
                        navController.popBackStack()
                    },
                )
            }
        }

        composable(
            route = VaultDestinations.SLIDESHOW,
            arguments = listOf(navArgument(VaultDestinations.ARG_CATEGORY) { type = NavType.StringType }),
        ) { entry ->
            val category = entry.category()
            val vm: SlideshowViewModel =
                viewModel(
                    key = "slideshow-${category.name}",
                    factory = viewModelFactory { initializer { SlideshowViewModel(category) } },
                )
            val items by vm.items.collectAsStateWithLifecycle()
            FolderSlideshowScreen(items = items, onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.RECYCLE_BIN) {
            RecycleBinScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.FAKE_PASSWORD) {
            FakePasswordScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.category(): VaultCategory {
    val name = arguments?.getString(VaultDestinations.ARG_CATEGORY)
    return VaultCategory.entries.firstOrNull { it.name == name } ?: VaultCategory.PHOTOS
}
