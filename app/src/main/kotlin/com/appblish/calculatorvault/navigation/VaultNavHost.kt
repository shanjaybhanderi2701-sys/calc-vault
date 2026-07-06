package com.appblish.calculatorvault.navigation

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.onboarding.OnboardingRoute
import com.appblish.calculatorvault.settings.ChangePinScreen
import com.appblish.calculatorvault.settings.PermissionManagementScreen
import com.appblish.calculatorvault.settings.SettingsLanguageScreen
import com.appblish.calculatorvault.settings.SettingsScreen
import com.appblish.calculatorvault.settings.ThemeScreen
import com.appblish.calculatorvault.vault.CategoryScreen
import com.appblish.calculatorvault.vault.CategoryViewModel
import com.appblish.calculatorvault.vault.HideImportScreen
import com.appblish.calculatorvault.vault.HideImportViewModel
import com.appblish.calculatorvault.vault.RecycleBinScreen
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.VaultHomeScreen
import com.appblish.calculatorvault.vault.VaultSearchScreen
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.ui.AllFilesPrimerSheet
import com.appblish.calculatorvault.vault.viewer.FolderSlideshowScreen
import com.appblish.calculatorvault.vault.viewer.PagerViewerScreen
import com.appblish.calculatorvault.vault.viewer.PagerViewerViewModel
import com.appblish.calculatorvault.vault.viewer.SlideshowViewModel

/**
 * The app spine, re-scoped to the Phase-1 build spec (APP-225). Starts on the
 * [VaultDestinations.GATE] splash, which routes to onboarding on first run or straight to
 * the calculator disguise for a returning user. The disguise is the only thing an onlooker
 * sees; typing a configured code on the calculator resolves the vault it opens (real or a
 * decoy) and its passphrase, landing directly on the vault home — no tab shell (design
 * call D-1), no upfront permission wall. All Files Access is primed contextually by the
 * D-2 bottom sheet on the first tap into a content surface (spec §5). Inside the vault the
 * home pushes category → hide/import, viewers, folder slideshow, search, the recycle bin,
 * and minimal Settings. There is no recovery surface anywhere (spec §0).
 */
@Composable
fun VaultNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext

    // Open the encrypted public-storage vault for the current session and land on the
    // home, dropping the disguise spine so back returns to the calculator. unlock() derives
    // the data key from the session passphrase and loads the namespaced .CalcVault/ index;
    // it is a safe no-op if either the passphrase or All Files Access is still missing.
    fun enterVault() {
        VaultGraph.contentRepository.unlock()
        navController.navigate(VaultDestinations.VAULT_HOME) {
            popUpTo(VaultDestinations.CALCULATOR)
            launchSingleTop = true
        }
    }

    // Re-lock on every background (APP-205). ProcessLifecycleOwner reports the whole app —
    // not a single activity — so ON_STOP fires only when CalcVault genuinely goes to the
    // background (not on rotation and not for in-app permission / delete-consent dialogs,
    // which merely pause the activity). When it fires while the user is inside the unlocked
    // vault we forget the session + data key and reset the back stack to the calculator, so
    // the next foreground shows the disguise and demands the PIN again. The single
    // exception is the primer's grant round-trip through system Settings, which arms a
    // one-shot suppression (see SessionLock.beginGrantRoundTrip) so the granted permission
    // can continue straight into the tapped category (design call D-2).
    DisposableEffect(navController) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP &&
                    SessionLock.isVaultSurface(navController.currentDestination?.route) &&
                    !SessionLock.consumeGrantRoundTrip()
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

    // Process-death restore (APP-240): the nav back stack survives the process via saved
    // instance state, but the in-memory session does not. If composition ever lands on a
    // vault surface with no live session — the cold-restore signature, unreachable by any
    // legitimate in-app navigation — reset the restored spine onto the calculator lock so
    // the vault never reappears without the PIN being typed again.
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry) {
        if (SessionLock.requiresLockOnColdRestore(currentEntry?.destination?.route)) {
            SessionLock.relock()
            navController.navigate(VaultDestinations.CALCULATOR) {
                popUpTo(VaultDestinations.CALCULATOR) { inclusive = true }
                launchSingleTop = true
            }
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
                // Spec §1.5: the last intro card lands on Home (Vault tab), not back on the
                // calculator. The calculator is still planted beneath so back / re-lock from
                // the home always reveals the disguise. The just-created PIN opens the real
                // vault; a blank PIN (defensive — should not happen) falls back to the
                // calculator alone.
                onComplete = { pin ->
                    navController.navigate(VaultDestinations.CALCULATOR) {
                        popUpTo(VaultDestinations.ONBOARDING) { inclusive = true }
                    }
                    if (pin.isNotEmpty()) {
                        VaultGraph.contentRepository.lock()
                        VaultSession.begin(pin, VaultDestinations.storageId(VaultKind.Real))
                        enterVault()
                    }
                },
            )
        }

        composable(VaultDestinations.CALCULATOR) {
            CalculatorScreen(
                // A matched code both identifies the vault (real/decoy → storage namespace)
                // and is the passphrase that derives its data key. Re-key the shared
                // repository for this session first so a previous session's content can
                // never leak across the calculator boundary (decoy isolation). The vault
                // opens immediately; All Files Access is primed contextually inside (§5).
                onUnlock = { kind, code ->
                    VaultGraph.contentRepository.lock()
                    VaultSession.begin(code, VaultDestinations.storageId(kind))
                    enterVault()
                },
            )
        }

        composable(VaultDestinations.VAULT_HOME) {
            val activityContext = LocalContext.current

            // Back-out to the calculator must forget the session + data key (APP-225 P1a):
            // otherwise the repository stays unlocked in memory behind the disguise. Lock
            // first, then pop to the calculator planted beneath by enterVault().
            BackHandler {
                SessionLock.lockNow()
                navController.popBackStack(VaultDestinations.CALCULATOR, inclusive = false)
            }

            // Contextual All-Files-Access gate (spec §5, design call D-2): content surfaces
            // prompt via the primer bottom sheet on first tap; the tapped destination is
            // parked and resumed the moment the grant round-trip returns successfully.
            var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
            var showPrimer by rememberSaveable { mutableStateOf(false) }

            fun openContent(route: String) {
                if (StoragePermissions.hasAllFilesAccess(activityContext)) {
                    VaultGraph.contentRepository.unlock()
                    navController.navigate(route)
                } else {
                    pendingRoute = route
                    showPrimer = true
                }
            }

            // Legacy (< API 30) grant path: a plain runtime dialog, no settings round-trip.
            val legacyLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val pending = pendingRoute
                    if (granted && pending != null) {
                        pendingRoute = null
                        VaultGraph.contentRepository.unlock()
                        navController.navigate(pending)
                    }
                }

            // Returning from the system All-Files-Access screen with the grant → continue
            // straight into the parked destination (docx: "automatically redirected").
            LifecycleResumeEffect(Unit) {
                val pending = pendingRoute
                if (pending != null && StoragePermissions.hasAllFilesAccess(activityContext)) {
                    pendingRoute = null
                    showPrimer = false
                    VaultGraph.contentRepository.unlock()
                    navController.navigate(pending)
                }
                onPauseOrDispose { }
            }

            VaultHomeScreen(
                onCategoryClick = { openContent(VaultDestinations.category(it)) },
                onRecentClick = { openContent(VaultDestinations.viewer(it.id, it.category)) },
                onRecycleBinClick = { openContent(VaultDestinations.RECYCLE_BIN) },
                onSearchClick = { navController.navigate(VaultDestinations.SEARCH) },
                onThemeClick = { navController.navigate(VaultDestinations.SETTINGS_THEME) },
                onSettingsClick = { navController.navigate(VaultDestinations.SETTINGS) },
            )

            if (showPrimer) {
                AllFilesPrimerSheet(
                    onAllow = {
                        showPrimer = false
                        if (StoragePermissions.usesRuntimeWritePermission()) {
                            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            StoragePermissions.allFilesAccessIntent(activityContext)?.let { intent ->
                                // The system Settings trip backgrounds the app; keep this
                                // one unlock session alive so the grant lands back inside
                                // the vault instead of behind the calculator.
                                SessionLock.beginGrantRoundTrip()
                                runCatching { activityContext.startActivity(intent) }
                            }
                        }
                    },
                    onDismiss = {
                        // Cancel / scrim tap: remain on the vault home; the surface stays
                        // gated until the next attempt (spec §5 denial behavior).
                        showPrimer = false
                        pendingRoute = null
                    },
                )
            }
        }

        composable(VaultDestinations.SEARCH) {
            VaultSearchScreen(
                onBack = { navController.popBackStack() },
                onOpenItem = { navController.navigate(VaultDestinations.viewer(it.id, it.category)) },
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
                onOpenItem = {
                    // The pager's page set must equal the grid the user tapped: the open
                    // folder's items, or the category root ("Recent") when no folder is open.
                    navController.navigate(
                        VaultDestinations.viewer(it.id, category, vm.state.value.openFolderId),
                    )
                },
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
            arguments =
                listOf(
                    navArgument(VaultDestinations.ARG_CATEGORY) { type = NavType.StringType },
                    navArgument(VaultDestinations.ARG_ITEM_ID) { type = NavType.StringType },
                    navArgument(VaultDestinations.ARG_FOLDER_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            val itemId = entry.arguments?.getString(VaultDestinations.ARG_ITEM_ID).orEmpty()
            val category = entry.category()
            val folderId = entry.arguments?.getString(VaultDestinations.ARG_FOLDER_ID)
            val viewerContext = LocalContext.current.applicationContext
            // Gallery-grade pager viewer (APP-235 P0): swipes across the tapped grid's page
            // set, zooms photos, plays video/audio via ExoPlayer per settled page.
            val vm: PagerViewerViewModel =
                viewModel(
                    key = "pager-$itemId",
                    factory =
                        viewModelFactory {
                            initializer { PagerViewerViewModel(itemId, category, folderId, viewerContext) }
                        },
                )
            PagerViewerScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
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

        // Minimal Phase-1 Settings (S22): language, change password, switch app icon,
        // theme, All Files Access status, hide-from-recents, about.
        composable(VaultDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onChangePin = { navController.navigate(VaultDestinations.SETTINGS_CHANGE_PIN) },
                onTheme = { navController.navigate(VaultDestinations.SETTINGS_THEME) },
                onPermissions = { navController.navigate(VaultDestinations.SETTINGS_PERMISSIONS) },
                onLanguage = { navController.navigate(VaultDestinations.SETTINGS_LANGUAGE) },
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

        composable(VaultDestinations.SETTINGS_LANGUAGE) {
            SettingsLanguageScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.category(): VaultCategory {
    val name = arguments?.getString(VaultDestinations.ARG_CATEGORY)
    return VaultCategory.entries.firstOrNull { it.name == name } ?: VaultCategory.PHOTOS
}
