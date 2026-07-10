package com.appblish.calculatorvault.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.appblish.calculatorvault.auth.AuthGraph
import com.appblish.calculatorvault.auth.VaultKind
import com.appblish.calculatorvault.calculator.CalculatorScreen
import com.appblish.calculatorvault.onboarding.OnboardingRoute
import com.appblish.calculatorvault.recovery.RecoveryEntryScreen
import com.appblish.calculatorvault.recovery.RecoverySetupIntroHost
import com.appblish.calculatorvault.recovery.RecoverySetupScreen
import com.appblish.calculatorvault.recovery.RecoveryUnlockScreen
import com.appblish.calculatorvault.settings.ChangePinScreen
import com.appblish.calculatorvault.settings.PermissionManagementScreen
import com.appblish.calculatorvault.settings.PinRecoveryScreen
import com.appblish.calculatorvault.settings.SettingsLanguageScreen
import com.appblish.calculatorvault.settings.SettingsScreen
import com.appblish.calculatorvault.settings.ThemeScreen
import com.appblish.calculatorvault.vault.CategoryScreen
import com.appblish.calculatorvault.vault.CategoryState
import com.appblish.calculatorvault.vault.CategoryViewModel
import com.appblish.calculatorvault.vault.HideImportScreen
import com.appblish.calculatorvault.vault.HideImportViewModel
import com.appblish.calculatorvault.vault.RecycleBinScreen
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.VaultHomeScreen
import com.appblish.calculatorvault.vault.VaultSearchScreen
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.actions.PhotoAction
import com.appblish.calculatorvault.vault.actions.PhotoActionCallbacks
import com.appblish.calculatorvault.vault.actions.PhotoActionsHost
import com.appblish.calculatorvault.vault.actions.rememberPhotoActionsController
import com.appblish.calculatorvault.vault.crypto.RecoveryMethod
import com.appblish.calculatorvault.vault.media.MediaSource
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.ui.AllFilesPrimerSheet
import com.appblish.calculatorvault.vault.viewer.FolderSlideshowScreen
import com.appblish.calculatorvault.vault.viewer.PagerViewerScreen
import com.appblish.calculatorvault.vault.viewer.PagerViewerViewModel
import com.appblish.calculatorvault.vault.viewer.SlideshowViewModel
import kotlinx.coroutines.launch

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
                // PIN Recovery doorways (spec §1.4): `11223344 =` and the 3-failed-attempt
                // affordance open the recovery landing. A doorway only — it resets nothing.
                onOpenRecovery = { navController.navigate(VaultDestinations.RECOVERY_ENTRY) },
            )
        }

        composable(VaultDestinations.VAULT_HOME) {
            val activityContext = LocalContext.current

            // Back at the vault-home root leaves the app entirely (APP-248): the disguise
            // and the unlocked vault must never share a back stack, so pressing back here
            // exits to the device home screen with the vault LOCKED, rather than popping
            // back to the calculator in an unlocked in-app session. Forget the session +
            // data key first (APP-225 P1a — otherwise the repository stays unlocked in
            // memory behind the disguise), then move the task to the background (launcher).
            // Backgrounding fires the ON_STOP observer above, which additionally resets the
            // restored spine onto the calculator lock, so the next foreground (warm or cold)
            // shows the calculator disguise and demands the PIN again.
            BackHandler {
                SessionLock.lockNow()
                activityContext.findActivity()?.moveTaskToBack(true)
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

            // PIN Recovery one-time setup intro (W0 01, ruling R1): offered once after the
            // first real vault operation when recovery is unconfigured; dismissing leaves the
            // grid banner (07) to keep nagging.
            RecoverySetupIntroHost(
                onSetUp = { navController.navigate(VaultDestinations.RECOVERY_SETUP) },
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
                onHide = {
                    // APP-299 P1-3: opened from inside a real vault album → hide flat into
                    // it; from vault home / the album grid (or the "Recent" pseudo-folder)
                    // → null, keeping the S16 source-bucket mapping.
                    val destinationFolderId =
                        vm.state.value.openFolderId
                            ?.takeUnless { it == CategoryState.RECENT_FOLDER_ID }
                    navController.navigate(VaultDestinations.hide(category, destinationFolderId))
                },
                onSetUpRecovery = { navController.navigate(VaultDestinations.RECOVERY_SETUP) },
            )
        }

        composable(
            route = VaultDestinations.HIDE,
            arguments =
                listOf(
                    navArgument(VaultDestinations.ARG_CATEGORY) { type = NavType.StringType },
                    navArgument(VaultDestinations.ARG_FOLDER_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            val category = entry.category()
            // APP-299 P1-3 launch context: non-null → hide flat into that vault album.
            val destinationFolderId = entry.arguments?.getString(VaultDestinations.ARG_FOLDER_ID)
            val hideContext = LocalContext.current.applicationContext
            val vm: HideImportViewModel =
                viewModel(
                    // Key includes the destination so opening the flow from album A gets a
                    // distinct VM from the vault-home flow (never reuses the wrong context).
                    key = "hide-${category.name}-${destinationFolderId ?: "root"}",
                    factory =
                        viewModelFactory {
                            initializer {
                                HideImportViewModel(
                                    category,
                                    mediaSource = MediaSource(hideContext),
                                    destinationFolderId = destinationFolderId,
                                )
                            }
                        },
                )
            val hideScope = rememberCoroutineScope()
            HideImportScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onHidden = {
                    // First successful hide/import is the "first real vault operation" that
                    // arms the recovery setup prompt (ruling R1) — real vault only, never a
                    // decoy. Idempotent, so every subsequent hide is a cheap no-op.
                    if (VaultSession.namespace.isEmpty()) {
                        hideScope.launch { AuthGraph.credentialStore.markRealVaultOpened() }
                    }
                    navController.popBackStack()
                },
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
            // Host = app-255's gallery-grade pager; app-254's §6–§9 photo-action dialogs are
            // layered on top and keyed to the pager's *active* page (vm.activeItem).
            val activeItem by vm.activeItem.collectAsStateWithLifecycle()
            val albums by vm.albums.collectAsStateWithLifecycle()
            val albumName by vm.albumName.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val controller = rememberPhotoActionsController()
            val snackbarHostState = remember { SnackbarHostState() }
            // Surface the §7 result copy (Move / Unhide / Delete / Permanent). The pager
            // itself advances as the list shrinks and pops back once the context empties.
            LaunchedEffect(message) {
                val text = message ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(text)
                vm.consumeMessage()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                PagerViewerScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    // §6 Move / §7 Unhide / §9 Property open the layered action dialogs
                    // for the active page (Unhide via the destination dialog — APP-293 P0-2).
                    onMove = { controller.open(PhotoAction.MOVE) },
                    onInfo = { controller.open(PhotoAction.PROPERTY) },
                    onUnhide = { controller.open(PhotoAction.UNHIDE) },
                )
                activeItem?.let { current ->
                    PhotoActionsHost(
                        controller = controller,
                        item = current,
                        albumName = albumName,
                        albums = albums,
                        callbacks =
                            PhotoActionCallbacks(
                                onMove = { folderId -> vm.move(folderId) },
                                onCreateFolder = { name -> vm.createFolder(name) },
                                onUnhide = { destination -> vm.unhide(destination) },
                                onMoveToBin = { vm.delete() },
                                onPermanentDelete = { vm.permanentlyDelete() },
                            ),
                    )
                }
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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

        // --- PIN Recovery (APP-321 W2) ---

        // Setup flow (W0 02–06): writes Wrap B + Wrap C for the session DEK on completion.
        composable(VaultDestinations.RECOVERY_SETUP) {
            RecoverySetupScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }

        // The doorway landing (W0 08): both `11223344 =` and the 3-fail affordance arrive
        // here. The method rows hand off to the W3 unlock+reset seam.
        composable(VaultDestinations.RECOVERY_ENTRY) {
            RecoveryEntryScreen(
                onAnswerMethod = { navController.navigate(VaultDestinations.recoveryUnlock("answer")) },
                onCodeMethod = { navController.navigate(VaultDestinations.recoveryUnlock("code")) },
                onBack = { navController.popBackStack() },
            )
        }

        // W3 (W0 09/10 → 11, APP-325): prove identity via Wrap B/C, then set a new PIN that
        // re-wraps Wrap A only. On success the vault is unlocked under the new PIN — drop the
        // recovery + calculator lock backstack and land on the vault home.
        composable(
            route = VaultDestinations.RECOVERY_UNLOCK,
            arguments = listOf(navArgument(VaultDestinations.ARG_RECOVERY_METHOD) { type = NavType.StringType }),
        ) { entry ->
            val method =
                if (entry.arguments?.getString(VaultDestinations.ARG_RECOVERY_METHOD) == "code") {
                    RecoveryMethod.RECOVERY_CODE
                } else {
                    RecoveryMethod.SECURITY_ANSWER
                }
            RecoveryUnlockScreen(
                method = method,
                onDone = {
                    navController.navigate(VaultDestinations.VAULT_HOME) {
                        popUpTo(VaultDestinations.CALCULATOR) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
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
                onPinRecovery = { navController.navigate(VaultDestinations.SETTINGS_PIN_RECOVERY) },
            )
        }

        composable(VaultDestinations.SETTINGS_PIN_RECOVERY) {
            PinRecoveryScreen(onBack = { navController.popBackStack() })
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

/**
 * Walk the [Context] wrapper chain to the hosting [Activity] (APP-248). `LocalContext` in a
 * Compose tree is a `ContextThemeWrapper`, not the Activity, so exiting the app to the
 * launcher via `moveTaskToBack` needs the underlying Activity resolved explicitly. Returns
 * null if no Activity is in the chain (should not happen for a hosted composable).
 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
