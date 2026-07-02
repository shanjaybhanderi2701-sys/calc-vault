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
import com.appblish.calculatorvault.pin.PinEntryScreen
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
 * App navigation. Disguise spine (calculator → PIN → vault shell) stays shallow so
 * leaving the vault always lands back on the innocuous calculator. Inside the vault the
 * shell pushes category → hide/import, viewers, folder slideshow, and the recycle bin.
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext

    // Open the encrypted public-storage vault and land on the shell, dropping the disguise
    // spine so back returns to the calculator. unlock() derives the data key from the
    // session passphrase and loads the .CalcVault/ index; it is a safe no-op if either the
    // passphrase or All Files Access is still missing.
    fun enterVault() {
        VaultGraph.contentRepository.unlock()
        navController.navigate(VaultDestinations.VAULT_SHELL) {
            popUpTo(VaultDestinations.CALCULATOR)
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = VaultDestinations.CALCULATOR,
    ) {
        composable(VaultDestinations.CALCULATOR) {
            CalculatorScreen(
                // The matched secret code is the vault passphrase: record it for the session
                // so the storage layer can derive the data key that unwraps .CalcVault/.
                onUnlock = { code ->
                    VaultSession.begin(code)
                    navController.navigate(VaultDestinations.PIN)
                },
            )
        }

        composable(VaultDestinations.PIN) {
            PinEntryScreen(
                onAuthenticated = {
                    // Point-of-need gate: the vault content lives in the public .CalcVault/
                    // folder, so opening it needs All Files Access. Show the primer first
                    // if it isn't granted yet; otherwise unlock and enter directly.
                    if (StoragePermissions.hasAllFilesAccess(context)) {
                        enterVault()
                    } else {
                        navController.navigate(VaultDestinations.STORAGE_PRIMER) {
                            // Drop the PIN screen so back from the primer returns to the calculator.
                            popUpTo(VaultDestinations.CALCULATOR)
                        }
                    }
                },
                onDismiss = { navController.popBackStack() },
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
                onSettingsClick = { /* Settings — Phase 5 */ },
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
            val context = LocalContext.current.applicationContext
            val vm: HideImportViewModel =
                viewModel(
                    key = "hide-${category.name}",
                    factory =
                        viewModelFactory {
                            initializer { HideImportViewModel(category, mediaSource = MediaSource(context)) }
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
    }
}

private fun androidx.navigation.NavBackStackEntry.category(): VaultCategory {
    val name = arguments?.getString(VaultDestinations.ARG_CATEGORY)
    return VaultCategory.entries.firstOrNull { it.name == name } ?: VaultCategory.PHOTOS
}
