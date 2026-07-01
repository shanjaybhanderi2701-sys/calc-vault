package com.appblish.calculatorvault.explore

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The six Quick Tools that make up the Explore tab (Epic H of the 65-flow deck). Each
 * entry carries the deck label, a one-line purpose, and a leading glyph. Glyphs are drawn
 * from `material-icons-core` (the only icon set on the classpath), so they approximate the
 * deck's custom line icons — the layout, hierarchy, and single-green accent are faithful.
 */
enum class ExploreTool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    JunkCleaner("Junk Cleaner", "Scan & clear cache and residual files", Icons.Filled.Delete),
    PrivateBrowser("Private Browser", "Browse with nothing saved to history", Icons.Filled.Search),
    WebsiteBlocker("Website Blocker", "Keep chosen sites from ever loading", Icons.Filled.Lock),
    Notes("Notes", "Private notes locked behind the vault", Icons.Filled.Edit),
    HideNotification("Hide Notification", "Silence notifications from chosen apps", Icons.Filled.Notifications),
    FakePassword("Fake Password", "A decoy PIN that opens a safe, empty vault", Icons.Filled.Face),
}
