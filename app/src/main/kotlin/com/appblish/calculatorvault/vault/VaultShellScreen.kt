package com.appblish.calculatorvault.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.ui.components.NavItem
import com.appblish.calculatorvault.ui.components.VaultBottomNav
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem

/**
 * The three-tab nav shell — **Vault · AppLock · Explore** — that hosts the vault home.
 * Only the Vault tab is built in Phase 2; AppLock (Phase 3) and Explore (Phase 4) show a
 * labeled placeholder so the shell and tab-switching are complete and reviewable now.
 */
@Composable
fun VaultShellScreen(
    onCategoryClick: (VaultCategory) -> Unit,
    onRecentClick: (VaultItem) -> Unit,
    onRecycleBinClick: () -> Unit,
    onDisguiseClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs =
        remember {
            listOf(
                NavItem("Vault", Icons.Filled.Home),
                NavItem("AppLock", Icons.Filled.Lock),
                NavItem("Explore", Icons.Filled.Search),
            )
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VaultTheme.colors.canvas,
        bottomBar = {
            VaultBottomNav(
                items = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 ->
                VaultHomeScreen(
                    onCategoryClick = onCategoryClick,
                    onRecentClick = onRecentClick,
                    onRecycleBinClick = onRecycleBinClick,
                    onDisguiseClick = onDisguiseClick,
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier.padding(innerPadding),
                )
            1 -> TabPlaceholder("AppLock", "Per-app lock arrives in Phase 3.", Modifier.padding(innerPadding))
            else -> TabPlaceholder("Explore", "Quick Tools arrive in Phase 4.", Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun TabPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = VaultTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(text = message, style = VaultTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}
