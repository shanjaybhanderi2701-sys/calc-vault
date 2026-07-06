package com.appblish.calculatorvault.vault.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appblish.calculatorvault.ui.components.DeleteChoiceDialog
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color as AndroidColor

/**
 * Gallery-grade full-screen viewer (APP-225 board feedback, P0-2): a [HorizontalPager]
 * across every item of the context the user opened the item from (folder or category
 * root), starting at the tapped item. Photos support pinch-to-zoom, double-tap-to-zoom
 * and pan; the pager only swipes at 1x so a zoomed pan never fights it. Video/audio pages
 * keep the Media3/ExoPlayer stage — only the settled page's blob is ever decrypted, and
 * swiping away releases the player and deletes the cleartext temp file (spec §7).
 *
 * Restore/Delete keep the single-item viewer's actions: Delete routes through the shared
 * delete-choice modal (D-4); both simply shrink the page set, so the pager advances to
 * the next item naturally and an emptied context invokes [onEmpty] (default: [onBack]).
 */
@Composable
fun PagerViewerScreen(
    viewModel: PagerViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEmpty: () -> Unit = onBack,
) {
    val colors = VaultTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activePage by viewModel.activePage.collectAsStateWithLifecycle()

    LaunchedEffect(state.empty) {
        if (state.empty) onEmpty()
    }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        if (state.loaded && state.pages.isNotEmpty()) {
            ViewerPager(
                state = state,
                activePage = activePage,
                viewModel = viewModel,
                onBack = onBack,
            )
        } else {
            // Context still loading (or just emptied and about to navigate back).
            CircularProgressIndicator(
                color = colors.accent,
                modifier = Modifier.size(32.dp).align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ViewerPager(
    state: PagerViewerState,
    activePage: ActivePage?,
    viewModel: PagerViewerViewModel,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.startIndex) { state.pages.size }
    var zoomed by remember { mutableStateOf(false) }
    var showDeleteChoice by remember { mutableStateOf(false) }

    // Decrypt only the settled page (spec §7): re-keying the VM's active page deletes the
    // previous page's temp file / drops its bytes. Also re-runs when a delete shrinks the
    // list and a new item slides into the settled slot.
    LaunchedEffect(pagerState.settledPage, state.pages) {
        state.pages.getOrNull(pagerState.settledPage)?.let { viewModel.setActivePage(it.id) }
    }
    // A newly settled page always starts un-zoomed (zoom never carries across pages).
    LaunchedEffect(pagerState.settledPage) {
        zoomed = false
    }

    val currentItem = state.pages.getOrNull(pagerState.currentPage.coerceIn(0, state.pages.lastIndex))
    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopBar(
            title = currentItem?.originalName.orEmpty(),
            subtitle = currentItem?.let { "${pagerState.currentPage + 1} of ${state.pages.size} · ${it.dateLabel}" },
            onBack = onBack,
        )
        HorizontalPager(
            state = pagerState,
            // Zoomed pan must not fight the pager: swiping is only a pager gesture at 1x.
            userScrollEnabled = !zoomed,
            key = { index -> state.pages[index].id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val item = state.pages[page]
            val isCurrent = pagerState.settledPage == page
            ViewerPage(
                item = item,
                content =
                    if (activePage != null && activePage.itemId == item.id) {
                        activePage.content
                    } else {
                        PageContent.Loading
                    },
                isCurrent = isCurrent,
                onZoomedChanged = { if (isCurrent) zoomed = it },
            )
        }
        PagerActionBar(
            onRestore = { currentItem?.let { viewModel.restore(it.id) } },
            onDelete = { showDeleteChoice = true },
        )
    }
    if (showDeleteChoice) {
        DeleteChoiceDialog(
            itemCount = 1,
            onMoveToRecycleBin = {
                showDeleteChoice = false
                currentItem?.let { viewModel.moveToRecycleBin(it.id) }
            },
            onDeletePermanently = {
                showDeleteChoice = false
                currentItem?.let { viewModel.deletePermanently(it.id) }
            },
            onDismiss = { showDeleteChoice = false },
        )
    }
}

/** One pager page, dispatched on the decrypted [content] — never a silent blank. */
@Composable
private fun ViewerPage(
    item: VaultItem,
    content: PageContent,
    isCurrent: Boolean,
    onZoomedChanged: (Boolean) -> Unit,
) {
    val colors = VaultTheme.colors
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        when (content) {
            PageContent.Loading ->
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.size(32.dp),
                )
            PageContent.Error -> ErrorPage()
            is PageContent.Media ->
                // Only the settled page plays; a page peeked mid-swipe shows the spinner.
                if (isCurrent) {
                    MediaPlayerPage(mediaFile = content.file)
                } else {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
                }
            is PageContent.Bytes ->
                when (item.category) {
                    VaultCategory.CONTACTS -> ContactPage(item = item, bytes = content.bytes)
                    else ->
                        ImagePage(
                            item = item,
                            bytes = content.bytes,
                            isCurrent = isCurrent,
                            onZoomedChanged = onZoomedChanged,
                        )
                }
        }
    }
}

/** Local result of the off-main bitmap decode — distinguishes "still decoding" from "failed". */
private sealed interface Decoded {
    data object Pending : Decoded

    data object Failed : Decoded

    class Ready(
        val bitmap: ImageBitmap,
    ) : Decoded
}

@Composable
private fun ImagePage(
    item: VaultItem,
    bytes: ByteArray,
    isCurrent: Boolean,
    onZoomedChanged: (Boolean) -> Unit,
) {
    val colors = VaultTheme.colors
    // Decode off the main thread (board complaint P0-2: no jank, no silent blank).
    val decoded by produceState<Decoded>(initialValue = Decoded.Pending, bytes) {
        value =
            withContext(Dispatchers.Default) {
                BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
                    ?.let { Decoded.Ready(it) } ?: Decoded.Failed
            }
    }
    when (val result = decoded) {
        Decoded.Pending ->
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
        Decoded.Failed ->
            // Files (PDFs etc.) legitimately don't decode as bitmaps → neutral glyph;
            // an undecodable *photo* blob is a real failure → error page.
            if (item.category == VaultCategory.PHOTOS) ErrorPage() else GlyphPage(item)
        is Decoded.Ready ->
            ZoomableImage(
                bitmap = result.bitmap,
                contentDescription = item.originalName,
                isCurrent = isCurrent,
                onZoomedChanged = onZoomedChanged,
            )
    }
}

/**
 * Full-bleed image with pinch-to-zoom (1x..[MAX_ZOOM]), double-tap-to-zoom
 * ([DOUBLE_TAP_ZOOM], centered on the tap; double-tap again resets) and pan clamped to
 * the zoomed bounds. Pan is only consumed while zoomed (`canPan`), so 1x drags reach the
 * pager; [onZoomedChanged] lets the screen disable pager swiping while zoomed. Leaving
 * the settled page resets to 1x.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    isCurrent: Boolean,
    onZoomedChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Keep the scaled image covering the container: max translation is the overflow half.
    fun clampOffset(
        candidate: Offset,
        atScale: Float,
    ): Offset {
        val maxX = containerSize.width * (atScale - 1f) / 2f
        val maxY = containerSize.height * (atScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offset = if (scale > MIN_ZOOM) clampOffset(offset + panChange, scale) else Offset.Zero
        }

    LaunchedEffect(scale > MIN_ZOOM) {
        onZoomedChanged(scale > MIN_ZOOM)
    }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = MIN_ZOOM
            offset = Offset.Zero
        }
    }

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > MIN_ZOOM) {
                                scale = MIN_ZOOM
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                offset = clampOffset((center - tap) * DOUBLE_TAP_ZOOM, DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                }.transformable(state = transformState, canPan = { scale > MIN_ZOOM })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
    )
}

/**
 * Plays a decrypted video/audio blob from [mediaFile] (app-private cache) via
 * Media3/ExoPlayer + [PlayerView] (spec §7 hard requirement). The player is released when
 * the page leaves composition; the temp file itself is owned by [PagerViewerViewModel],
 * which deletes it when the settled page changes and sweeps all of them in onCleared().
 */
@Composable
private fun MediaPlayerPage(mediaFile: File) {
    val context = LocalContext.current
    val player =
        remember(mediaFile) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(mediaFile)))
                prepare()
                playWhenReady = true
            }
        }
    DisposableEffect(mediaFile) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Stable PlayerView surface only (customization setters are @UnstableApi
            // and would trip the UnsafeOptInUsageError lint gate).
            PlayerView(ctx).apply {
                this.player = player
                setBackgroundColor(AndroidColor.BLACK)
            }
        },
        update = { view -> view.player = player },
    )
}

/** Decrypt failure page — an explicit glyph + message, never a blank screen (P0-2). */
@Composable
private fun ErrorPage() {
    val colors = VaultTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.padding(VaultTheme.spacing.xl),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.destructive,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Couldn't open this file",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Neutral category-glyph page for non-renderable kinds (e.g. a PDF in Files). */
@Composable
private fun GlyphPage(item: VaultItem) {
    val colors = VaultTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.padding(VaultTheme.spacing.xl),
    ) {
        Icon(
            imageVector = item.category.icon(),
            contentDescription = null,
            tint = item.category.color(),
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = item.originalName,
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact contact card: glyph + name + the vCard's leading lines. */
@Composable
private fun ContactPage(
    item: VaultItem,
    bytes: ByteArray,
) {
    val colors = VaultTheme.colors
    val vcard = remember(bytes) { bytes.toString(Charsets.UTF_8) }
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
        Text(
            text = vcard
                .lineSequence()
                .take(6)
                .joinToString("\n")
                .ifBlank { "Hidden contact" },
            style = VaultTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Bottom action bar: Restore + Delete only, matching the single-item viewer (S18 — no
 * Share/export in Phase 1; spec §8 vocabulary "Restore", never "unhide").
 */
@Composable
private fun PagerActionBar(
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = VaultTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = VaultTheme.spacing.sm),
    ) {
        // Restore: decrypt back to public storage so it returns to the gallery.
        PagerAction(label = "Restore", tint = colors.textPrimary, onClick = onRestore) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restore", tint = colors.textPrimary)
        }
        PagerAction(label = "Delete", tint = colors.destructive, onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.destructive)
        }
    }
}

@Composable
private fun PagerAction(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { icon() }
        Text(text = label, style = VaultTheme.typography.labelMedium, color = tint)
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
