package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon

/**
 * Single-item viewer, dispatched by category: full-bleed image (Photos/Files preview),
 * video player, audio player, or contact card. Playback surfaces render the decrypted
 * blob — here as neutral placeholders until the media pipeline streams bytes through
 * [com.appblish.calculatorvault.vault.crypto.VaultCrypto]. A shared bottom bar offers
 * Share / Delete (→ recycle bin).
 */
@Composable
fun ItemViewerScreen(
    item: VaultItem,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: () -> Unit = {},
) {
    val colors = VaultTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = item.originalName, subtitle = item.dateLabel, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (item.category) {
                VaultCategory.VIDEOS -> VideoStage()
                VaultCategory.AUDIOS -> AudioStage(item)
                VaultCategory.CONTACTS -> ContactStage(item)
                else -> ImageStage(item) // Photos + Files preview
            }
        }
        ViewerActionBar(onShare = onShare, onDelete = onDelete)
    }
}

@Composable
private fun ImageStage(item: VaultItem) {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(VaultTheme.spacing.lg)
                .aspectRatio(0.75f)
                .clip(VaultTheme.shapes.card)
                .background(colors.surfaceVariant),
    ) {
        Icon(
            imageVector = item.category.icon(),
            contentDescription = null,
            tint = item.category.color(),
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun VideoStage() {
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(VaultTheme.spacing.lg)
                .aspectRatio(16f / 9f)
                .clip(VaultTheme.shapes.card)
                .background(colors.surfaceVariant),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.accent),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = colors.onAccent,
                modifier = Modifier.size(32.dp),
            )
        }
        Scrubber(modifier = Modifier.align(Alignment.BottomCenter).padding(VaultTheme.spacing.md))
    }
}

@Composable
private fun AudioStage(item: VaultItem) {
    val colors = VaultTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.lg),
        modifier = Modifier.padding(VaultTheme.spacing.xxl),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant),
        ) {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = null,
                tint = item.category.color(),
                modifier = Modifier.size(56.dp),
            )
        }
        Text(item.originalName, style = VaultTheme.typography.titleMedium, color = colors.textPrimary)
        Scrubber(modifier = Modifier.fillMaxWidth())
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.accent),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = colors.onAccent,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun ContactStage(item: VaultItem) {
    val colors = VaultTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.padding(VaultTheme.spacing.xxl),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(item.category.color().copy(alpha = 0.2f)),
        ) {
            Icon(
                imageVector = item.category.icon(),
                contentDescription = null,
                tint = item.category.color(),
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = item.originalName,
            style = VaultTheme.typography.headlineSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text("Hidden contact", style = VaultTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

@Composable
private fun Scrubber(modifier: Modifier = Modifier) {
    // Static progress track; real seek position binds when playback lands.
    val colors = VaultTheme.colors
    Box(modifier = modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.divider)) {
        Box(modifier = Modifier.fillMaxWidth(0.35f).height(4.dp).clip(CircleShape).background(colors.accent))
    }
}

@Composable
private fun ViewerActionBar(
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = VaultTheme.spacing.sm),
    ) {
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Share", tint = colors.textPrimary)
        }
        Spacer(Modifier.size(VaultTheme.spacing.xxl))
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.destructive)
        }
    }
}
