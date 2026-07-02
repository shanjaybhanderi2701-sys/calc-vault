package com.appblish.calculatorvault.vault

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.appblish.calculatorvault.applock.ui.AppLockScreen
import com.appblish.calculatorvault.explore.ExploreScreen
import com.appblish.calculatorvault.explore.ExploreTool
import com.appblish.calculatorvault.ui.components.NavItem
import com.appblish.calculatorvault.ui.components.VaultBottomNav
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem

/**
 * The three-tab nav shell — **Vault · AppLock · Explore** — that hosts the vault home
 * (Phase 2), the per-app AppLock manager (Phase 3), and the Explore Quick Tools (Phase 4;
 * each tool pushes onto the nav graph via [onExploreToolClick]).
 */
@Composable
fun VaultShellScreen(
    onCategoryClick: (VaultCategory) -> Unit,
    onRecentClick: (VaultItem) -> Unit,
    onRecycleBinClick: () -> Unit,
    onDisguiseClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onExploreToolClick: (ExploreTool) -> Unit,
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
            1 -> AppLockScreen(modifier = Modifier.padding(innerPadding))
            else -> ExploreScreen(onToolClick = onExploreToolClick, modifier = Modifier.padding(innerPadding))
        }
    }
}
