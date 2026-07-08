package com.appblish.calculatorvault.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.theme.VaultGridTokens
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * The W3-E album-level **Choose cover** picker (design W3-D §6): a full-screen
 * radio-select grid over the album's photos — tap selects exactly one, tap another moves
 * the ring — with the album's current cover chip and a bottom "Change cover photo" action that
 * arms only once a non-current tile is chosen. `‹`/system back cancels without a write.
 *
 * **Cached-thumbnail-only** (spec §1.7/§3.7): every tile renders through [loadThumbnail]
 * — the same LRU + encrypted stored-thumb pipeline as the shipped grids — so opening,
 * scrolling, and confirming never decrypt a full-size blob (CI asserts).
 *
 * [items] arrive in the album's current display order (§7) so the picker matches the
 * grid the user just saw. Ring state is screen-local: navigate-away-and-back clears it.
 */
@Composable
fun ChooseCoverScreen(
    items: List<VaultItem>,
    currentCoverId: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    loadThumbnail: suspend (VaultItem) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    var selectedId by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Choose cover", onBack = onCancel)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(vertical = spacing.md),
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg)
                    .testTag("choose-cover-grid"),
        ) {
            items(items, key = { it.id }) { item ->
                CoverCandidateTile(
                    item = item,
                    selected = item.id == selectedId,
                    isCurrent = item.id == currentCoverId,
                    onClick = { selectedId = item.id },
                    loadThumbnail = loadThumbnail,
                )
            }
        }
        val armedId = selectedId?.takeUnless { it == currentCoverId }
        PillButton(
            text = "Change cover photo",
            enabled = armedId != null,
            onClick = { armedId?.let(onConfirm) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.md)
                    .testTag("choose-cover-confirm"),
        )
    }
}

/** One candidate tile: cached thumbnail + cover.selectRing when chosen + "Current" chip. */
@Composable
private fun CoverCandidateTile(
    item: VaultItem,
    selected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    loadThumbnail: suspend (VaultItem) -> ImageBitmap?,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val thumbnail: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = loadThumbnail(item)
    }
    Box(
        modifier =
            Modifier
                .padding(spacing.xs)
                .aspectRatio(1f)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant)
                .clickable(onClick = onClick)
                .testTag("cover-tile-${item.id}"),
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = item.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(
                            width = VaultGridTokens.CoverRingWidth,
                            color = VaultGridTokens.CoverRingColor,
                            shape = VaultTheme.shapes.thumbnail,
                        ),
            )
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = VaultGridTokens.CoverRingColor,
                modifier = Modifier.align(Alignment.TopStart).padding(spacing.xs).size(20.dp),
            )
        }
        if (isCurrent) {
            Text(
                text = "Current",
                style = VaultTheme.typography.labelMedium,
                color = colors.textPrimary,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = spacing.xs)
                        .clip(VaultTheme.shapes.card)
                        .background(colors.surface.copy(alpha = 0.85f))
                        .padding(horizontal = spacing.sm)
                        .testTag("cover-current-chip"),
            )
        }
    }
}
