package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.applock.AppLockLogic
import com.appblish.calculatorvault.applock.InstalledApp
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * The "Please select the app you want to lock" multi-add picker, faithful to the board's
 * xlock role-model: a search field, a pre-checked **Suggested** section above the rest, and a
 * bottom **LOCK (n)** CTA that reflects the running count. Only currently-unlocked apps are
 * pickable; already-locked apps are managed by the list's toggles.
 */
@Composable
fun AppPickerScreen(
    apps: List<InstalledApp>,
    onLock: (Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = VaultTheme.spacing
    val lockable = remember(apps) { apps.filter { !it.locked } }
    val suggested = remember(lockable) { lockable.filter { it.suggested } }
    val others = remember(lockable) { lockable.filterNot { it.suggested } }

    val checked: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    // Pre-check the board's suggested set on first composition.
    LaunchedEffect(suggested) {
        suggested.forEach { if (it.packageName !in checked) checked[it.packageName] = true }
    }

    var query by remember { mutableStateOf("") }
    val visibleSuggested = AppLockLogic.search(suggested, query)
    val visibleOthers = AppLockLogic.search(others, query)
    val selectedCount = checked.count { it.value }

    Column(modifier = modifier.fillMaxSize()) {
        VaultTopBar(title = "Select apps to lock", onBack = onBack)
        AppSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (visibleSuggested.isNotEmpty()) {
                item { SectionHeader(title = "Suggested") }
                items(visibleSuggested, key = { "s-${it.packageName}" }) { app ->
                    AppPickRow(
                        app = app,
                        checked = checked[app.packageName] == true,
                        onCheckedChange = { checked[app.packageName] = it },
                    )
                }
            }
            if (visibleOthers.isNotEmpty()) {
                item { SectionHeader(title = "All apps") }
                items(visibleOthers, key = { it.packageName }) { app ->
                    AppPickRow(
                        app = app,
                        checked = checked[app.packageName] == true,
                        onCheckedChange = { checked[app.packageName] = it },
                    )
                }
            }
        }

        PillButton(
            text = if (selectedCount == 0) "Lock" else "Lock ($selectedCount)",
            onClick = { onLock(checked.filterValues { it }.keys.toSet()) },
            enabled = selectedCount > 0,
            modifier = Modifier.padding(spacing.lg),
        )
    }
}
