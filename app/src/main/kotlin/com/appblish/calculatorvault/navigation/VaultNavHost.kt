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
import com.appblish.calculatorvault.vault.CategoryScreen
import com.appblish.calculatorvault.vault.CategoryViewModel
import com.appblish.calculatorvault.vault.HideImportScreen
import com.appblish.calculatorvault.vault.HideImportViewModel
import com.appblish.calculatorvault.vault.RecycleBinScreen
import com.appblish.calculatorvault.vault.VaultShellScreen
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.viewer.FolderSlideshowScreen
import com.appblish.calculatorvault.vault.viewer.ItemViewerScreen
import com.appblish.calculatorvault.vault.viewer.SlideshowViewModel
import com.appblish.calculatorvault.vault.viewer.ViewerViewModel

/**
 * App navigation. The disguise spine is shallow (calculator → vault shell) so leaving the
 * vault always lands back on the innocuous calculator. The calculator itself is the real
 * gate — the Phase-1 PIN pad (APP-158): typing the configured 4-digit code and pressing
 * `=` resolves to the vault via the credential store and opens the shell; anything else is
 * ordinary arithmetic, so there is no separate PIN screen to reveal the vault. Inside the
 * vault the shell pushes category → hide/import, viewers, folder slideshow, and the
 * recycle bin.
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
                // A resolved PIN opens the vault. Phase 2 has a single vault shell, so both
                // the real vault and any decoy land here; per-[VaultKind] storage routing is
                // the Phase-6 integration (APP-163).
                onUnlock = {
                    navController.navigate(VaultDestinations.VAULT_SHELL) {
                        // Drop nothing extra; back from the vault returns to the calculator.
                        popUpTo(VaultDestinations.CALCULATOR)
                    }
                },
                // Recovery (forgot-password) is wired in the Phase-6 integration; the hidden
                // long-press gesture is inert in the standalone Phase-2 build.
                onForgotPin = {},
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
