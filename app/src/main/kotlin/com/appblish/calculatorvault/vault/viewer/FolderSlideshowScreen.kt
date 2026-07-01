package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.delay

/**
 * Folder slideshow: steps through a folder's items one at a time. Tap the left/right
 * halves to page manually, or Play to auto-advance every [SLIDE_MS]. The stage renders
 * the decrypted image (placeholder until the media pipeline lands). Loops at the ends.
 */
@Composable
fun FolderSlideshowScreen(
    items: List<VaultItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(playing, items.size) {
        if (playing && items.size > 1) {
            while (true) {
                delay(SLIDE_MS)
                index = (index + 1) % items.size
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        if (items.isEmpty()) {
            Text(
                text = "This folder is empty",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val current = items[index.coerceIn(0, items.lastIndex)]
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(VaultTheme.spacing.lg)
                            .clip(VaultTheme.shapes.card)
                            .background(colors.surfaceVariant)
                            .fillMaxHeight(0.7f),
                ) {
                    Icon(
                        imageVector = current.category.icon(),
                        contentDescription = current.originalName,
                        tint = current.category.color(),
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            // Tap zones for manual paging.
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().clickable {
                            index = (index - 1 + items.size) % items.size
                        },
                )
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().clickable {
                            index = (index + 1) % items.size
                        },
                )
            }
        }

        // Controls overlay.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(VaultTheme.spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = colors.textPrimary)
            }
            Text(
                text = if (items.isEmpty()) "" else "${index + 1} / ${items.size}",
                style = VaultTheme.typography.labelLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        if (items.size > 1) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(VaultTheme.spacing.xl)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable { playing = !playing },
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = colors.onAccent,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

private const val SLIDE_MS = 2500L
