package com.appblish.calculatorvault.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblish.calculatorvault.ui.theme.VaultTheme

/** One bottom-nav destination (Vault, AppLock, Explore). */
data class NavItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * The bottom tab bar from the deck. The active tab is tinted with the single green
 * accent; inactive tabs are muted. Settings lives as a top-bar gear, not here.
 */
@Composable
fun VaultBottomNav(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    NavigationBar(
        containerColor = colors.surface,
        modifier = modifier,
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                label = { Text(text = item.label, style = VaultTheme.typography.labelMedium) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.onAccent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.accent,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary,
                    ),
            )
        }
    }
}
