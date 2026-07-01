package com.appblish.calculatorvault.vault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Per-category icon glyph. Pulled from `material-icons-core` (the only icon artifact on
 * the classpath), so these are the nearest stock stand-ins for the deck's bespoke
 * category glyphs — swap for the exact deck icons when the extended icon set / vector
 * assets land. Chip color comes from [VaultCategory.chipColor].
 */
fun VaultCategory.icon(): ImageVector =
    when (this) {
        VaultCategory.PHOTOS -> Icons.Filled.Star
        VaultCategory.VIDEOS -> Icons.Filled.PlayArrow
        VaultCategory.AUDIOS -> Icons.Filled.Favorite
        VaultCategory.FILES -> Icons.Filled.List
        VaultCategory.CONTACTS -> Icons.Filled.Person
    }

/** The category's icon-chip color as a Compose [Color]. */
fun VaultCategory.color(): Color = Color(chipColor)
