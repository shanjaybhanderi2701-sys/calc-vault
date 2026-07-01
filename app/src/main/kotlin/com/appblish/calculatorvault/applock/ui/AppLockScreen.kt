package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.appblish.calculatorvault.applock.AppLockFilter
import com.appblish.calculatorvault.applock.AppLockPermissions
import com.appblish.calculatorvault.applock.AppLockUiState
import com.appblish.calculatorvault.applock.AppLockViewModel
import com.appblish.calculatorvault.applock.InstalledApp
import com.appblish.calculatorvault.ui.components.ListRow
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.components.RowTrailing
import com.appblish.calculatorvault.ui.theme.VaultTheme

private enum class AppLockPage { Main, Picker, Permissions, Intruder, Settings }

/**
 * The **AppLock** tab (Phase 3), replacing the Phase 2 placeholder in the vault shell. Hosts
 * its own lightweight page state — the app list, the multi-add picker, the permission primer
 * (native-trust framing), the protection settings, and the intruder log — so the whole
 * feature lives inside the single AppLock tab without touching the top-level nav graph.
 */
@Composable
fun AppLockScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: AppLockViewModel =
        viewModel(
            factory = viewModelFactory { initializer { AppLockViewModel(context.applicationContext) } },
        )
    val state by vm.state.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(AppLockPage.Main) }
    var enforcementReady by remember { mutableStateOf(true) }

    // Re-read permission state and app list every time the tab resumes (e.g. back from the
    // system settings screens the primer launches).
    LifecycleResumeEffect(Unit) {
        enforcementReady = AppLockPermissions.isEnforcementReady(context)
        vm.refresh()
        onPauseOrDispose { }
    }

    when (page) {
        AppLockPage.Main ->
            AppLockMainPage(
                state = state,
                enforcementReady = enforcementReady,
                onToggle = { app, checked ->
                    vm.setLocked(app.packageName, checked)
                    if (checked && !enforcementReady) page = AppLockPage.Permissions
                },
                onFilter = vm::setFilter,
                onQuery = vm::setQuery,
                onLockSuggested = { vm.lockAll(state.suggestedUnlocked.map { it.packageName }) },
                onDismissSuggested = vm::dismissSuggestions,
                onOpenPicker = { page = AppLockPage.Picker },
                onOpenSettings = { page = AppLockPage.Settings },
                onOpenIntruder = { page = AppLockPage.Intruder },
                onOpenPermissions = { page = AppLockPage.Permissions },
                modifier = modifier,
            )

        AppLockPage.Picker ->
            AppPickerScreen(
                apps = state.apps,
                onLock = { picks ->
                    vm.lockAll(picks)
                    page = if (picks.isNotEmpty() && !enforcementReady) AppLockPage.Permissions else AppLockPage.Main
                },
                onBack = { page = AppLockPage.Main },
                modifier = modifier,
            )

        AppLockPage.Permissions ->
            AppLockPermissionScreen(onBack = { page = AppLockPage.Main }, modifier = modifier)

        AppLockPage.Settings ->
            AppLockSettingsScreen(
                settings = state.settings,
                onChange = vm::updateSettings,
                onBack = { page = AppLockPage.Main },
                modifier = modifier,
            )

        AppLockPage.Intruder ->
            IntruderLogScreen(
                events = state.intruderEvents,
                onClear = vm::clearIntruderLog,
                onBack = { page = AppLockPage.Main },
                modifier = modifier,
            )
    }
}

@Composable
private fun AppLockMainPage(
    state: AppLockUiState,
    enforcementReady: Boolean,
    onToggle: (InstalledApp, Boolean) -> Unit,
    onFilter: (AppLockFilter) -> Unit,
    onQuery: (String) -> Unit,
    onLockSuggested: () -> Unit,
    onDismissSuggested: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIntruder: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val showSuggested = state.suggestedUnlocked.isNotEmpty() && !state.hasSeenSuggestions

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = spacing.lg, top = spacing.md, end = spacing.sm),
            ) {
                Text(
                    text = "AppLock",
                    style = VaultTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenIntruder) {
                    Icon(Icons.Filled.Face, contentDescription = "Intruder log", tint = colors.textPrimary)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Protection settings", tint = colors.textPrimary)
                }
            }
        }

        if (!enforcementReady) {
            item {
                ListRow(
                    title = "Turn on protection",
                    subtitle = "Grant Usage Access and Accessibility so locked apps prompt every time.",
                    leadingIcon = Icons.Filled.Lock,
                    trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
                    onClick = onOpenPermissions,
                )
            }
        }

        item {
            SegmentedFilter(
                selected = state.filter,
                onSelect = onFilter,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
        }
        item {
            AppSearchField(
                query = state.query,
                onQueryChange = onQuery,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
        }

        if (showSuggested) {
            item {
                SectionHeader(title = "Suggested") {
                    Text(
                        text = "Lock all",
                        style = VaultTheme.typography.labelLarge,
                        color = colors.accent,
                        modifier =
                            Modifier
                                .clip(VaultTheme.shapes.chip)
                                .clickable(onClick = onLockSuggested)
                                .padding(horizontal = spacing.sm, vertical = spacing.xs),
                    )
                }
            }
            items(state.suggestedUnlocked, key = { "sug-${it.packageName}" }) { app ->
                AppLockRow(app = app, onToggle = { checked -> onToggle(app, checked) })
            }
            item {
                Text(
                    text = "Dismiss suggestions",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier =
                        Modifier
                            .clickable(onClick = onDismissSuggested)
                            .padding(horizontal = spacing.lg, vertical = spacing.sm),
                )
            }
        }

        item { SectionHeader(title = "Apps") }

        when {
            state.loading ->
                item {
                    InfoLine("Loading apps…")
                }
            state.visibleApps.isEmpty() ->
                item {
                    InfoLine("No apps match this filter.")
                }
            else ->
                items(state.visibleApps, key = { it.packageName }) { app ->
                    AppLockRow(app = app, onToggle = { checked -> onToggle(app, checked) })
                }
        }

        item {
            PillButton(
                text = "Choose apps to lock",
                onClick = onOpenPicker,
                style = PillButtonStyle.Secondary,
                leadingIcon = Icons.Filled.Add,
                modifier = Modifier.padding(spacing.lg),
            )
        }
        item { Spacer(Modifier.height(spacing.xxl)) }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = VaultTheme.typography.bodyMedium,
        color = VaultTheme.colors.textSecondary,
        modifier = Modifier.padding(VaultTheme.spacing.lg),
    )
}
