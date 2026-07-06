package com.appblish.calculatorvault.vault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblish.calculatorvault.vault.model.VaultCategory

/**
 * Per-category icon glyph. `material-icons-core` is the only icon artifact on the
 * classpath, so the two glyphs it lacks — a photo and a music note — are built here from
 * the standard Material `Image` / `MusicNote` path data via the core builders. Photos'
 * old ★ and Audios' old ♥ read as "Favorites", not media types (APP-234 spec §2.4).
 * Chip color comes from [VaultCategory.chipColor].
 */
fun VaultCategory.icon(): ImageVector =
    when (this) {
        VaultCategory.PHOTOS -> PhotoGlyph
        VaultCategory.VIDEOS -> Icons.Filled.PlayArrow
        VaultCategory.AUDIOS -> MusicNoteGlyph
        VaultCategory.FILES -> Icons.Filled.List
        VaultCategory.CONTACTS -> Icons.Filled.Person
    }

/** The category's icon-chip color as a Compose [Color]. */
fun VaultCategory.color(): Color = Color(chipColor)

/** Material `Filled.Image` — a framed photo with mountains — for the Photos category. */
private val PhotoGlyph: ImageVector by lazy {
    materialIcon(name = "Vault.Photo") {
        materialPath {
            moveTo(21.0f, 19.0f)
            verticalLineTo(5.0f)
            curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
            horizontalLineTo(5.0f)
            curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
            verticalLineTo(19.0f)
            curveTo(3.0f, 20.1f, 3.9f, 21.0f, 5.0f, 21.0f)
            horizontalLineTo(19.0f)
            curveTo(20.1f, 21.0f, 21.0f, 20.1f, 21.0f, 19.0f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineTo(14.5f, 12.0f)
            lineToRelative(4.5f, 6.0f)
            horizontalLineTo(5.0f)
            lineToRelative(3.5f, -4.5f)
            close()
        }
    }
}

/** Material `Filled.MusicNote` — an eighth note — for the Audios category. */
private val MusicNoteGlyph: ImageVector by lazy {
    materialIcon(name = "Vault.MusicNote") {
        materialPath {
            moveTo(12.0f, 3.0f)
            verticalLineToRelative(10.55f)
            curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2.0f, -0.55f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(3.0f)
            horizontalLineToRelative(-6.0f)
            close()
        }
    }
}
