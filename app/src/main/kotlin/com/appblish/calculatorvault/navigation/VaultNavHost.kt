package com.appblish.calculatorvault.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.appblish.calculatorvault.explore.blocker.WebsiteBlockerScreen
import com.appblish.calculatorvault.explore.browser.PrivateBrowserScreen
import com.appblish.calculatorvault.explore.junk.JunkCleanerScreen
import com.appblish.calculatorvault.explore.notes.NoteEditorScreen
import com.appblish.calculatorvault.explore.notes.NotesScreen
import com.appblish.calculatorvault.explore.notification.HideNotificationScreen
import com.appblish.calculatorvault.fakepassword.FakePasswordScreen
import com.appblish.calculatorvault.onboarding.OnboardingRoute
import com.appblish.calculatorvault.recovery.ForgotPasswordRoute
import com.appblish.calculatorvault.settings.BackupScreen
import com.appblish.calculatorvault.settings.ChangePinScreen
import com.appblish.calculatorvault.settings.DisguiseScreen
import com.appblish.calculatorvault.settings.PermissionManagementScreen
import com.appblish.calculatorvault.settings.SettingsGraph
import com.appblish.calculatorvault.settings.SettingsScreen
import com.appblish.calculatorvault.settings.ThemeScreen
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
import com.appblish.calculatorvault.explore.fakepassword.FakePasswordScreen as ExploreFakePasswordScreen

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

    // Re-lock on every background (APP-205). ProcessLifecycleOwner reports the whole app —
    // not a single activity — so ON_STOP fires only when CalcVault genuinely goes to the
    // background (not on rotation and not for in-app permission / delete-consent dialogs,
    // which merely pause the activity). When it fires while the user is inside the unlocked
    // vault we forget the session + data key and reset the back stack to the calculator, so
    // the next foreground shows the disguise and demands the PIN again. Resetting here (while
    // hidden) rather than on the next resume means no vault content ever flashes on return.
    DisposableEffect(navController) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP &&
                    SettingsGraph.relockOnBackgroundEnabled &&
                    SessionLock.isVaultSurface(navController.currentDestination?.route)
                ) {
                    SessionLock.relock()
                    navController.navigate(VaultDestinations.CALCULATOR) {
                        popUpTo(VaultDestinations.CALCULATOR) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
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
                // The home "Switch app icon" button + at-risk banner open the icon-disguise
                // control (activity-alias swap), NOT the decoy fake-password flow (APP-215).
                onDisguiseClick = { navController.navigate(VaultDestinations.DISGUISE) },
                onSettingsClick = { navController.navigate(VaultDestinations.SETTINGS) },
                onExploreToolClick = { navController.navigate(VaultDestinations.exploreRoute(it)) },
            )
        }

        // --- Explore / Quick Tools (Phase 4) ------------------------------
        composable(VaultDestinations.EXPLORE_JUNK) {
            JunkCleanerScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.EXPLORE_BROWSER) {
            PrivateBrowserScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.EXPLORE_BLOCKER) {
            WebsiteBlockerScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.EXPLORE_NOTES) {
            NotesScreen(
                onBack = { navController.popBackStack() },
                onOpenNote = { navController.navigate(VaultDestinations.noteEditor(it)) },
                onNewNote = { navController.navigate(VaultDestinations.noteEditor(VaultDestinations.NEW_NOTE)) },
            )
        }

        composable(
            route = VaultDestinations.NOTE_EDITOR,
            arguments = listOf(navArgument(VaultDestinations.ARG_NOTE_ID) { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString(VaultDestinations.ARG_NOTE_ID)
            val noteId = raw?.takeUnless { it == VaultDestinations.NEW_NOTE }
            NoteEditorScreen(noteId = noteId, onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.EXPLORE_NOTIFICATION) {
            HideNotificationScreen(onBack = { navController.popBackStack() })
        }

        composable(VaultDestinations.EXPLORE_FAKE_PASSWORD) {
            ExploreFakePasswordScreen(onBack = { navController.popBackStack() })
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
                    onUnhide = {
                        vm.unhide()
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
                onDisguise = { navController.navigate(VaultDestinations.DISGUISE) },
            )
        }

        composable(VaultDestinations.DISGUISE) {
            DisguiseScreen(onBack = { navController.popBackStack() })
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

private fun androidx.navigation.NavBackStackEntry.category(): VaultCategory {
    val name = arguments?.getString(VaultDestinations.ARG_CATEGORY)
    return VaultCategory.entries.firstOrNull { it.name == name } ?: VaultCategory.PHOTOS
}
