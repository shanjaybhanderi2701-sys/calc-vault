package com.appblish.calculatorvault.vault.viewer

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color as AndroidColor

/**
 * CalcVault Phase B · Wave 1 · W1-E1 — full-screen photo-vault viewer (spec §2.1, design
 * sign-off [APP-253] §4). A [HorizontalPager] across the album's items (the context the
 * user opened the tapped item from), rendered full-bleed over a true-black canvas with
 * floating chrome:
 *
 *  - **Top bar** — back · centered `[ n / total ]` position · rotate · info.
 *  - **Bottom bar** — Unhide · Delete · Move (spec §1 terminology lock: the gallery-exit
 *    verb is *Unhide*, never "Restore"; "Restore" is Bin→vault only).
 *
 * **Gesture priority (hard rule, spec §2.1):** photos pinch-zoom (1×–5×), double-tap-zoom
 * to 2.5× centred on the tap and pan while zoomed; the pager only swipes at scale 1.0
 * (`userScrollEnabled = !zoomed`) and pan is only consumed while zoomed (`canPan`), so a
 * zoomed pan and a page swipe never fight. A single tap toggles the chrome; zooming force-
 * hides it. Rotate is a **live in-session** 90°/tap transform (persistence is Wave 3, W3-E)
 * and resets when the settled page changes.
 *
 * **Hard rules enforced:** the whole app window carries `FLAG_SECURE` (MainActivity), so
 * the viewer inherits it. Decrypt-to-view is strictly in memory for photos
 * ([PageContent.Bytes], decoded off-main); video/audio stream to an app-private cache temp
 * that the VM sweeps — no decrypted byte ever reaches browsable storage (Unhide via W1-E2
 * is the only exit). Only the settled page is ever decrypted and the grid's cached
 * thumbnail pipeline is untouched, so opening/closing the viewer never re-decrypts the grid.
 *
 * The bottom-bar Move/Info actions surface as callbacks wired by W1-E2; Delete routes
 * through the shared delete-choice modal (D-4). Deletes/unhides simply shrink the page set
 * (the pager advances naturally); an emptied context invokes [onEmpty].
 */
@Composable
fun PagerViewerScreen(
    viewModel: PagerViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEmpty: () -> Unit = onBack,
    onMove: (VaultItem) -> Unit = {},
    onInfo: (VaultItem) -> Unit = {},
    // APP-293 P0-1/P0-2: Unhide routes through the §7 destination dialog (original vs
    // chosen folder) exactly like Move/Info — never a blind restore-to-original.
    onUnhide: (VaultItem) -> Unit = {},
) {
    val colors = VaultTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activePage by viewModel.activePage.collectAsStateWithLifecycle()

    LaunchedEffect(state.empty) {
        if (state.empty) onEmpty()
    }

    Box(modifier = modifier.fillMaxSize().background(ViewerCanvas)) {
        if (state.loaded && state.pages.isNotEmpty()) {
            ViewerPager(
                state = state,
                activePage = activePage,
                viewModel = viewModel,
                onBack = onBack,
                onMove = onMove,
                onInfo = onInfo,
                onUnhide = onUnhide,
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
    onMove: (VaultItem) -> Unit,
    onInfo: (VaultItem) -> Unit,
    onUnhide: (VaultItem) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.startIndex) { state.pages.size }
    var zoomed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    // The settled photo's display rotation, seeded from its **persisted** net orientation
    // (W3-E, spec §2.2) and accumulated per ⟳ tap. Kept un-modded so the 200ms turn
    // animates forward through 360°; the VM commits it mod 360 (500ms idle / page change /
    // exit — W3-D §8), so four taps net to the stored value and write nothing.
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showDeleteChoice by remember { mutableStateOf(false) }

    // Decrypt only the settled page (spec §1/§7): re-keying the VM's active page deletes the
    // previous page's temp file / drops its bytes. Also re-runs when a delete shrinks the
    // list and a new item slides into the settled slot.
    LaunchedEffect(pagerState.settledPage, state.pages) {
        state.pages.getOrNull(pagerState.settledPage)?.let { viewModel.setActivePage(it.id) }
    }
    // A newly settled page starts un-zoomed, showing its own persisted orientation.
    LaunchedEffect(pagerState.settledPage) {
        zoomed = false
        rotationDegrees = state.pages.getOrNull(pagerState.settledPage)?.rotationDegrees ?: 0
    }
    // Zooming hides the chrome so it never floats over an inspected photo; returning to 1×
    // brings it back (design §4: "keeps bars hidden until zoom returns to 1.0"). Keyed on
    // [zoomed] alone, so a plain single-tap toggle at 1× is not clobbered by this effect.
    LaunchedEffect(zoomed) {
        chromeVisible = !zoomed
    }

    val currentItem = state.pages.getOrNull(pagerState.currentPage.coerceIn(0, state.pages.lastIndex))
    val position = "${pagerState.currentPage + 1} / ${state.pages.size}"

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // Zoomed pan must not fight the pager: swiping is only a pager gesture at 1x.
            userScrollEnabled = !zoomed,
            key = { index -> state.pages[index].id },
            modifier = Modifier.fillMaxSize(),
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
                // Non-settled pages show their own persisted orientation (peek/swipe-by).
                rotationDegrees = if (isCurrent) rotationDegrees else item.rotationDegrees,
                onZoomedChanged = { if (isCurrent) zoomed = it },
                // Single tap toggles the chrome (never while zoomed — the photo owns the tap).
                onToggleChrome = { if (!zoomed) chromeVisible = !chromeVisible },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                position = position,
                onBack = onBack,
                onRotate = {
                    // Rotate targets the settled, decoded photo (a decode-error page has
                    // nothing to rotate — W1-D §4 state). Net orientation goes to the VM,
                    // which owns the debounced persist (W3-E §8).
                    val settled = state.pages.getOrNull(pagerState.settledPage)
                    if (settled != null && activePage?.content is PageContent.Bytes) {
                        rotationDegrees += 90
                        viewModel.noteRotation(settled.id, rotationDegrees)
                    }
                },
                onInfo = { currentItem?.let(onInfo) },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerBottomBar(
                onUnhide = { currentItem?.let(onUnhide) },
                onDelete = { showDeleteChoice = true },
                onMove = { currentItem?.let(onMove) },
                // W3-E §5: the pre-agreed W1-D "4th action → ⋯ More" rule, executed. Null
                // (hidden) when the photo has no album to cover (category root/"Recent").
                onSetCover = if (currentItem?.folderId != null) ({ viewModel.setAsCover() }) else null,
            )
        }
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
    rotationDegrees: Int,
    onZoomedChanged: (Boolean) -> Unit,
    onToggleChrome: () -> Unit,
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
                // Only the settled page previews/plays; a page peeked mid-swipe spins.
                if (isCurrent) {
                    VideoPage(mediaFile = content.file, onToggleChrome = onToggleChrome)
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
                            rotationDegrees = rotationDegrees,
                            onZoomedChanged = onZoomedChanged,
                            onToggleChrome = onToggleChrome,
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
    rotationDegrees: Int,
    onZoomedChanged: (Boolean) -> Unit,
    onToggleChrome: () -> Unit,
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
                rotationDegrees = rotationDegrees,
                onZoomedChanged = onZoomedChanged,
                onToggleChrome = onToggleChrome,
            )
    }
}

/**
 * Full-bleed image with pinch-to-zoom (1x..[MAX_ZOOM]), double-tap-to-zoom
 * ([DOUBLE_TAP_ZOOM], centered on the tap; double-tap again resets), pan clamped to the
 * zoomed bounds, and a live in-session [rotationDegrees] transform. Pan is only consumed
 * while zoomed (`canPan`), so 1x drags reach the pager; [onZoomedChanged] lets the screen
 * disable pager swiping while zoomed. A single tap forwards to [onToggleChrome]. Leaving
 * the settled page resets to 1x.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    isCurrent: Boolean,
    rotationDegrees: Int,
    onZoomedChanged: (Boolean) -> Unit,
    onToggleChrome: () -> Unit,
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
    // Rotation resets zoom/pan to fit (W3-D §8) — rotating a zoomed crop disorients.
    LaunchedEffect(rotationDegrees) {
        scale = MIN_ZOOM
        offset = Offset.Zero
    }
    // 200ms per-tap turn (W3-D §8); the un-modded degrees keep it spinning forward.
    val animatedRotation by animateFloatAsState(
        targetValue = rotationDegrees.toFloat(),
        animationSpec = tween(durationMillis = 200),
        label = "viewer-rotation",
    )

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
                        onTap = { onToggleChrome() },
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
                    rotationZ = animatedRotation
                },
    )
}

/**
 * Video/audio page (APP-293 P0-3): playback is **tap-to-start, never automatic**. Until
 * the user taps the play button the page shows the full-screen preview frame (decoded
 * off-main from the already-decrypted cache temp — no extra decrypt) under a
 * semi-transparent centred play button; the surrounding chrome (Unhide/Delete/Move/More +
 * Info) stays available exactly as on a photo page, and a tap outside the button toggles
 * it. Tapping play swaps in the ExoPlayer surface.
 */
@Composable
private fun VideoPage(
    mediaFile: File,
    onToggleChrome: () -> Unit,
) {
    var playing by remember(mediaFile) { mutableStateOf(false) }
    if (playing) {
        MediaPlayerPage(mediaFile)
    } else {
        VideoPreviewPage(mediaFile = mediaFile, onPlay = { playing = true }, onToggleChrome = onToggleChrome)
    }
}

/** The pre-playback preview: decoded frame + `viewer.playButton` over the black canvas. */
@Composable
private fun VideoPreviewPage(
    mediaFile: File,
    onPlay: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    // First frame from the decrypted temp file, off the main thread. Audio blobs have no
    // frame — the play button over the canvas is the whole preview.
    val frame by produceState<ImageBitmap?>(initialValue = null, mediaFile) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(mediaFile.absolutePath)
                        retriever.frameAtTime?.asImageBitmap()
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
    ) {
        frame?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Semi-transparent, properly-sized play affordance — playback starts ONLY here.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ViewerScrim)
                    .clickable(onClick = onPlay),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = ViewerOnCanvas,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

/**
 * Plays a decrypted video/audio blob from [mediaFile] (app-private cache) via
 * Media3/ExoPlayer + [PlayerView] (spec §7 hard requirement). The player is released when
 * the page leaves composition; the temp file itself is owned by [PagerViewerViewModel]'s
 * in-session cache, which evicts old entries and sweeps everything in onCleared().
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
            text = "Couldn't open this photo",
            style = VaultTheme.typography.bodyMedium,
            color = ViewerOnCanvasMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Neutral category-glyph page for non-renderable kinds (e.g. a PDF in Files). */
@Composable
private fun GlyphPage(item: VaultItem) {
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
            color = ViewerOnCanvasMuted,
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
            color = ViewerOnCanvas,
            textAlign = TextAlign.Center,
        )
        Text(
            text = vcard
                .lineSequence()
                .take(6)
                .joinToString("\n")
                .ifBlank { "Hidden contact" },
            style = VaultTheme.typography.bodySmall,
            color = ViewerOnCanvasMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Floating top bar over the top scrim (design §4): back · centered `[ n / total ]` pager
 * position · rotate · info. No solid fill — the photo stays the hero; the scrim only lends
 * the glyphs legibility over any image.
 */
@Composable
private fun ViewerTopBar(
    position: String,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(ViewerScrim, Color.Transparent)))
                .padding(horizontal = VaultTheme.spacing.sm, vertical = VaultTheme.spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = ViewerOnCanvas)
        }
        Text(
            text = position,
            style = VaultTheme.typography.labelLarge,
            color = ViewerOnCanvas,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRotate) {
            Icon(Icons.Filled.Refresh, contentDescription = "Rotate", tint = ViewerOnCanvas)
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Filled.Info, contentDescription = "Info", tint = ViewerOnCanvas)
        }
    }
}

/**
 * Floating bottom bar over the bottom scrim (design §4): the three shipped single-purpose
 * targets — **Unhide** (gallery-exit; spec §1 terminology lock), **Delete** (Error tint on
 * glyph + label only), **Move** (relocate the index entry to another album, W1-E2) — plus
 * the W3-E fourth equal target **`⋯ More`**, executing W1-D §4's pre-agreed rule verbatim:
 * a menu (`viewer.moreMenu`), never a truncation, holding the single Wave-3 item
 * **Change cover photo**. [onSetCover] null hides `⋯ More` entirely (no album to cover).
 */
@Composable
private fun ViewerBottomBar(
    onUnhide: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onSetCover: (() -> Unit)?,
) {
    val colors = VaultTheme.colors
    var moreMenuOpen by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, ViewerScrim)))
                .padding(vertical = VaultTheme.spacing.sm),
    ) {
        // Unhide: decrypt this blob back out to the gallery (original-or-chosen, W1-E2).
        // Its glyph is the unlock (APP-293 P0-1) — Share is a different action entirely.
        ViewerAction(label = "Unhide", tint = ViewerOnCanvas, icon = VaultActionIcons.Unhide, onClick = onUnhide)
        ViewerAction(label = "Delete", tint = colors.destructive, icon = Icons.Filled.Delete, onClick = onDelete)
        // Move: relocate the encrypted index entry to another vault album (stays encrypted).
        ViewerAction(label = "Move", tint = ViewerOnCanvas, icon = VaultActionIcons.MoveTo, onClick = onMove)
        if (onSetCover != null) {
            Box {
                ViewerAction(
                    label = "More",
                    tint = ViewerOnCanvas,
                    icon = Icons.Filled.MoreVert,
                    onClick = { moreMenuOpen = true },
                )
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Change cover photo") },
                        onClick = {
                            moreMenuOpen = false
                            onSetCover()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(
    label: String,
    tint: Color,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint) }
        Text(text = label, style = VaultTheme.typography.labelMedium, color = tint)
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

// Viewer chrome colors (design §3 VaultViewerTokens): a true-black canvas so the photo is
// the hero, on-canvas glyphs/text in white, and a 50%-ink scrim behind the floating bars.
private val ViewerCanvas = Color(0xFF000000)
private val ViewerOnCanvas = Color(0xFFFFFFFF)
private val ViewerOnCanvasMuted = Color(0xB3FFFFFF)
private val ViewerScrim = Color(0x80000000)
