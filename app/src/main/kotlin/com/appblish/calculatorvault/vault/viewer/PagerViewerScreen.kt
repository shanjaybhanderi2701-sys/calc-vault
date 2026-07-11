package com.appblish.calculatorvault.vault.viewer

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.ui.AspectRatioFrameLayout
import com.appblish.calculatorvault.ui.components.DeleteChoiceDialog
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.VaultGraph
import com.appblish.calculatorvault.vault.media.VaultThumbnailPipeline
import com.appblish.calculatorvault.vault.media.VideoStoryboard
import com.appblish.calculatorvault.vault.media.VideoStoryboardCache
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.share.ShareSessionLauncher
import com.appblish.calculatorvault.vault.ui.color
import com.appblish.calculatorvault.vault.ui.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CalcVault Phase B · Wave 3 · APP-371 (F4) — a side-loaded external subtitle bound to the
 * currently playing video. [uri] is a `content://` (device/SAF pick) or a `vault://item/<id>`
 * ([fromVault] = true) opaque uri; the player builds a [SingleSampleMediaSource] over it and
 * merges it with the video (see `MediaPlayerPage.buildMediaSource`).
 */
internal data class LoadedSubtitle(
    val uri: String,
    val mimeType: String,
    val label: String,
    val fromVault: Boolean,
)

/**
 * CalcVault Phase B · Wave 3 · APP-371 (F1–F3) — the §5d playlist context handed down to the
 * video player so the playlist sheet, Next/Prev, tap-switch, and the five order modes work.
 *
 * The playlist **is** the pager's current-folder video pages; navigation is expressed as a
 * pager page switch ([onSelect]/[onNext]/[onPrevious]) and [onCompleted] auto-advance, all
 * routed through [PlaylistEngine] so the order-mode semantics live in one JVM-tested place.
 * Recreated each recomposition, so [currentIndex] always mirrors the settled page.
 */
internal class VideoPlaylistController(
    val items: List<VaultItem>,
    val currentIndex: Int,
    val orderMode: OrderMode,
    val onOrderModeChanged: (OrderMode) -> Unit,
    val onSelect: (Int) -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onCompleted: () -> Unit,
)

/**
 * CalcVault Phase B · APP-379 — the vault file-management actions (back + Info/Share/Move/
 * Unhide/Delete) for a video/audio page. On a photo page these live on the floating
 * [ViewerBottomBar]; on a video/audio page that permanent bar (and the photo [ViewerTopBar])
 * is suppressed so the player is edge-to-edge and immersive, and the same actions are reached
 * only from the player's **temporary** top-bar ⋯ overflow — never a file-management bar
 * wrapped around the video (spec §5c immersive contract; the tester's device-test complaint).
 */
internal class ViewerFileActions(
    val onBack: () -> Unit,
    val onInfo: () -> Unit,
    val onShare: () -> Unit,
    val onMove: () -> Unit,
    val onUnhide: () -> Unit,
    val onDelete: () -> Unit,
)

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
    val shareRequest by viewModel.shareRequest.collectAsStateWithLifecycle()

    LaunchedEffect(state.empty) {
        if (state.empty) onEmpty()
    }

    // APP-294 Share: launches the chooser for a prepared temp-copy session and purges it
    // the moment the share flow returns (completed or cancelled).
    ShareSessionLauncher(
        request = shareRequest,
        onLaunched = viewModel::shareLaunched,
        onFinished = viewModel::shareFinished,
    )

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
    // APP-314 P0: the whole decrypted window {n-1, n, n+1} — every composed page renders its
    // own already-decrypted bytes from here, so a forward/back swipe never shows a spinner.
    val pageWindow by viewModel.pageWindow.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = state.startIndex) { state.pages.size }
    var zoomed by remember { mutableStateOf(false) }
    // APP-428: the pager gates its swipe on **actual playback**, not on page type. This mirrors
    // the settled video/audio page's live player state (reported up from [VideoPage]). Preview /
    // browse (not playing) → the pager is swipeable so the user can flick between videos; an
    // active player → paging is locked so a horizontal drag belongs to seek/scrub, not next-video.
    var playerActive by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    // The settled photo's display rotation, seeded from its **persisted** net orientation
    // (W3-E, spec §2.2) and accumulated per ⟳ tap. Kept un-modded so the 200ms turn
    // animates forward through 360°; the VM commits it mod 360 (500ms idle / page change /
    // exit — W3-D §8), so four taps net to the stored value and write nothing.
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var showDeleteChoice by remember { mutableStateOf(false) }
    // APP-299 P0-1: retain the decoded bitmaps of recently-viewed pages so swipe-back is
    // instant (no re-decode) even though the pager disposes off-screen pages. Small cap —
    // decoded full-res bitmaps are heavy; a handful covers "swipe forward then back".
    val bitmapCache = remember { ViewerBitmapCache<ImageBitmap>(BITMAP_CACHE_MAX) }

    // --- APP-371 (F1–F3) · §5d playlist over the pager's current-folder video pages ---------
    // Navigation is a pager page switch; PlaylistEngine owns the order-mode arithmetic. The
    // controller is rebuilt each recomposition so currentIndex mirrors the settled page.
    val playlistScope = rememberCoroutineScope()
    var orderMode by remember { mutableStateOf(OrderMode.ORDER) }
    // A deterministic shuffle permutation, rebuilt on mode/size change (seed = size so it is
    // reproducible and stable across a config change without re-shuffling mid-playback).
    val shuffleOrder = remember(orderMode, state.pages.size) {
        if (orderMode == OrderMode.SHUFFLE) {
            PlaylistEngine.shuffledOrder(state.pages.size, state.pages.size.toLong())
        } else {
            emptyList()
        }
    }

    fun jumpToPage(index: Int) {
        if (index in state.pages.indices) playlistScope.launch { pagerState.animateScrollToPage(index) }
    }
    val playlistController =
        VideoPlaylistController(
            items = state.pages,
            currentIndex = pagerState.currentPage,
            orderMode = orderMode,
            onOrderModeChanged = { orderMode = it },
            onSelect = { jumpToPage(it) },
            onNext = { PlaylistEngine.manualNext(state.pages.size, pagerState.currentPage)?.let { jumpToPage(it) } },
            onPrevious = {
                PlaylistEngine
                    .manualPrev(
                        state.pages.size,
                        pagerState.currentPage
                    )?.let { jumpToPage(it) }
            },
            onCompleted = {
                val size = state.pages.size
                val cur = pagerState.currentPage
                if (orderMode == OrderMode.SHUFFLE && shuffleOrder.isNotEmpty()) {
                    // Advance within the shuffled order; single pass (stop after the last).
                    val pos = shuffleOrder.indexOf(cur)
                    if (pos in 0 until shuffleOrder.lastIndex) jumpToPage(shuffleOrder[pos + 1])
                } else {
                    // ORDER/NO_LOOP → next or stop; LOOP_ALL → wrap. (REPEAT_CURRENT never ends.)
                    val next = PlaylistEngine.onCompletion(size, cur, orderMode)
                    if (next != null && next != cur) jumpToPage(next)
                }
            },
        )

    // Decrypt only the settled page (spec §1/§7): re-keying the VM's active page deletes the
    // previous page's temp file / drops its bytes. Also re-runs when a delete shrinks the
    // list and a new item slides into the settled slot.
    LaunchedEffect(pagerState.settledPage, state.pages) {
        state.pages.getOrNull(pagerState.settledPage)?.let { viewModel.setActivePage(it.id) }
    }
    // APP-419 (P0-A): pre-warm the adjacent video posters into the pipeline LRU so swiping to the
    // next/previous video shows its pre-generated thumbnail instantly (photos are already
    // pre-decrypted by the VM's {n-1, n, n+1} window; a video page carries only an id and so is
    // not, hence this explicit n±1 warm). Cheap — a cache hit no-ops, a miss decrypts one ~200px
    // thumb off-main; never the full video.
    val previewContext = LocalContext.current
    val previewRepository = VaultGraph.contentRepository
    LaunchedEffect(pagerState.settledPage, state.pages) {
        val neighbours = listOf(pagerState.settledPage - 1, pagerState.settledPage + 1)
        for (i in neighbours) {
            val neighbour = state.pages.getOrNull(i) ?: continue
            if (neighbour.category == VaultCategory.VIDEOS) {
                runCatching { VaultThumbnailPipeline.load(previewContext, neighbour, previewRepository) }
            }
        }
    }
    // A newly settled page starts un-zoomed, showing its own persisted orientation. It also
    // starts in the preview (non-playing) state, so the pager is swipeable again the instant a
    // video is settled onto — the newly-composed [VideoPage] re-reports its real state (APP-428).
    LaunchedEffect(pagerState.settledPage) {
        zoomed = false
        playerActive = false
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

    // APP-379: a video/audio page is an immersive player — it owns the whole screen and
    // renders its OWN controls, so the photo-viewer's floating file-management chrome (the
    // top bar + the Unhide/Delete/Move/Share bottom bar) must NOT wrap it. The same file
    // actions are threaded into the player instead, reachable from its temporary ⋯ overflow.
    val currentIsPlayable =
        currentItem?.category == VaultCategory.VIDEOS || currentItem?.category == VaultCategory.AUDIOS
    val fileActions =
        ViewerFileActions(
            onBack = onBack,
            onInfo = { currentItem?.let(onInfo) },
            onShare = { viewModel.share() },
            onMove = { currentItem?.let(onMove) },
            onUnhide = { currentItem?.let(onUnhide) },
            onDelete = { showDeleteChoice = true },
        )

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // Zoomed pan must not fight the pager: swiping is only a pager gesture at 1x.
            //
            // APP-428 (APP-417 R6) · gate paging on **actual playback state**, NOT on page type.
            // These are two states of the same pager and need opposite behaviour:
            //  - Preview / browse (video NOT playing) → the pager IS swipeable, so the user flicks
            //    between videos and each shows its large cached poster (owner R6: "the preview
            //    screen was NOT restored" — round 5c had wrongly gated on `currentIsPlayable`, the
            //    page-type flag, so a video page was never swipeable even before playback started).
            //  - Player active (video IS playing) → paging is locked so a horizontal drag reaches
            //    [VideoPlayerSurface]'s seek/scrub gesture instead of switching video (APP-398 —
            //    the part the owner confirmed correct; kept, now keyed on real playback).
            // Switching videos still works via the transport Next/Prev + playlist (both call
            // animateScrollToPage, which is programmatic and unaffected by this flag).
            userScrollEnabled = !zoomed && !playerActive,
            // Keep the immediate neighbours composed (APP-299 P0-1) so the common
            // swipe-forward-then-back never even disposes the page — instant return.
            beyondViewportPageCount = 1,
            key = { index -> state.pages[index].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = state.pages[page]
            val isCurrent = pagerState.settledPage == page
            ViewerPage(
                item = item,
                // Render from the pre-decrypted window: the settled page and both neighbours
                // already hold Bytes; only a page outside {n-1, n, n+1} shows Loading.
                content = pageWindow[item.id] ?: PageContent.Loading,
                isCurrent = isCurrent,
                // Non-settled pages show their own persisted orientation (peek/swipe-by).
                rotationDegrees = if (isCurrent) rotationDegrees else item.rotationDegrees,
                onZoomedChanged = { if (isCurrent) zoomed = it },
                // Single tap toggles the chrome (never while zoomed — the photo owns the tap).
                onToggleChrome = { if (!zoomed) chromeVisible = !chromeVisible },
                bitmapCache = bitmapCache,
                fileActions = fileActions,
                playlist = playlistController,
                // APP-428: only the settled video/audio page reports its live playback state,
                // which drives the pager's swipe gate above.
                onPlayingChanged = { playerActive = it },
            )
        }

        // APP-379: the photo-viewer chrome only ever wraps a photo — a video/audio page is an
        // immersive player that supplies its own controls + back + ⋯ file overflow.
        AnimatedVisibility(
            visible = chromeVisible && !currentIsPlayable,
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
            visible = chromeVisible && !currentIsPlayable,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerBottomBar(
                onUnhide = { currentItem?.let(onUnhide) },
                onDelete = { showDeleteChoice = true },
                onMove = { currentItem?.let(onMove) },
                // APP-294: Share the settled page via the vault-safe temp-copy contract.
                onShare = { viewModel.share() },
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
    bitmapCache: ViewerBitmapCache<ImageBitmap>,
    fileActions: ViewerFileActions,
    playlist: VideoPlaylistController,
    onPlayingChanged: (Boolean) -> Unit,
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
                // APP-428: every video/audio page renders its large cached poster preview — the
                // settled page AND a page peeked mid-swipe — so flicking between videos shows each
                // thumbnail instantly (no spinner). Only the settled page can actually start the
                // player ([VideoPage] gates play on `isCurrent`); a neighbour always shows its poster.
                VideoPage(
                    item = item,
                    hasVideoFrame = content.hasVideoFrame,
                    isCurrent = isCurrent,
                    fileActions = fileActions,
                    playlist = playlist,
                    onPlayingChanged = onPlayingChanged,
                )
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
                            bitmapCache = bitmapCache,
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
    bitmapCache: ViewerBitmapCache<ImageBitmap>,
) {
    val colors = VaultTheme.colors
    // APP-299 P0-1: an already-decoded page is served straight from the in-session bitmap
    // cache — no Pending flash, no re-decode — so swipe-back is instant. A cold page decodes
    // off the main thread (board complaint P0-2: no jank, no silent blank) then caches the
    // result. Keyed on the item id so a swipe-back to the same page reuses the cache.
    val cached = bitmapCache.get(item.id)
    val decoded by produceState<Decoded>(
        initialValue = if (cached != null) Decoded.Ready(cached) else Decoded.Pending,
        item.id,
        bytes,
    ) {
        if (bitmapCache.get(item.id) != null) return@produceState // cache hit → already Ready
        value =
            withContext(Dispatchers.Default) {
                BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
                    ?.let { Decoded.Ready(it) } ?: Decoded.Failed
            }.also { if (it is Decoded.Ready) bitmapCache.put(item.id, it.bitmap) }
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
    // Pinch/pan write scale+offset synchronously (the pager reads `userScrollEnabled =
    // !zoomed` off this the same frame — a zoomed pan and a page swipe must never fight).
    // The double-tap runs a short coroutine that animates the same plain state, so the step
    // is smooth without making pinch async.
    var scale by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    // Pan bounds derive from the *fitted* image, not the container (APP-299 P2-4): a
    // ContentScale.Fit image is letterboxed, so clamping to the container let the user drag
    // an edge inside the frame ("past edges"). Fitted-content bounds make every corner
    // reachable and no edge overscroll, and stay correct for a rotated (90/270°) photo.
    fun clampAt(
        candidate: Offset,
        atScale: Float,
    ): Offset {
        val fitted =
            ViewerZoomMath.fittedContentSize(
                containerSize.width.toFloat(),
                containerSize.height.toFloat(),
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                rotationDegrees,
            )
        val max =
            ViewerZoomMath.maxPan(containerSize.width.toFloat(), containerSize.height.toFloat(), fitted, atScale)
        return ViewerZoomMath.clamp(candidate, max)
    }

    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offset = if (scale > MIN_ZOOM) clampAt(offset + panChange, scale) else Offset.Zero
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
                        // Double-tap steps to a ~2× zoom centred on the tapped point, and a
                        // second double-tap steps back out — both smoothly animated over a
                        // single fraction so scale and pan stay in lock-step.
                        onDoubleTap = { tap ->
                            val startScale = scale
                            val startOffset = offset
                            val targetScale = if (scale > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM
                            val targetOffset =
                                if (targetScale > MIN_ZOOM) {
                                    // Keep the tapped point stationary under the zoom (correct
                                    // centring is (center − tap)·(scale − 1), not ·scale), then
                                    // clamp so the step never lands outside the pannable bounds.
                                    clampAt(
                                        ViewerZoomMath.focusOffset(
                                            tap,
                                            containerSize.width.toFloat(),
                                            containerSize.height.toFloat(),
                                            targetScale,
                                        ),
                                        targetScale,
                                    )
                                } else {
                                    Offset.Zero
                                }
                            scope.launch {
                                animate(0f, 1f, animationSpec = tween(DOUBLE_TAP_ANIM_MS)) { t, _ ->
                                    scale = startScale + (targetScale - startScale) * t
                                    offset =
                                        Offset(
                                            startOffset.x + (targetOffset.x - startOffset.x) * t,
                                            startOffset.y + (targetOffset.y - startOffset.y) * t,
                                        )
                                }
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
 * off-main by **streaming** the encrypted blob through a [VaultMediaDataSource] — no
 * plaintext temp file, APP-347) under a semi-transparent centred play button; the
 * surrounding chrome (Unhide/Delete/Move/More + Info) stays available exactly as on a photo
 * page, and a tap outside the button toggles it. Tapping play swaps in the ExoPlayer surface.
 *
 * APP-379: both the preview and the playing player are **immersive** — the photo-viewer's
 * file-management chrome never wraps them. [fileActions] (back + Info/Share/Move/Unhide/
 * Delete) is surfaced only through the temporary top-bar ⋯ overflow instead.
 */
@Composable
private fun VideoPage(
    item: VaultItem,
    hasVideoFrame: Boolean,
    isCurrent: Boolean,
    fileActions: ViewerFileActions,
    playlist: VideoPlaylistController,
    onPlayingChanged: (Boolean) -> Unit,
) {
    // Wave 4 (APP-351): Expand from the mini player must resume the full player immediately —
    // start in the playing state (adopting the session's live player) instead of the preview.
    val session = rememberMiniPlayerSession()
    var playing by remember(item.id) { mutableStateOf(session.isExpandingInto(item.id)) }
    // APP-428: only the SETTLED page can actually play — a page peeked mid-swipe always shows its
    // poster preview, so browsing between videos never spins up an off-screen player. `active` is
    // therefore the real, on-screen playback state.
    val active = isCurrent && playing
    // Report the current page's real playback state up so the pager gates its swipe on playback
    // (preview → swipeable, playing → locked). Only the current page reports, so a peeked
    // neighbour (always `active == false`) can never clobber the settled page's state.
    LaunchedEffect(active, isCurrent) {
        if (isCurrent) onPlayingChanged(active)
    }
    // Swiping away drops back to the preview, so a return shows the poster (never a stale, still
    // off-screen player) and the pager stays swipeable.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) playing = false
    }
    if (active) {
        MediaPlayerPage(item.id, fileActions, playlist)
    } else {
        VideoPreviewPage(
            item = item,
            hasVideoFrame = hasVideoFrame,
            onPlay = { if (isCurrent) playing = true },
            fileActions = fileActions,
        )
    }
}

/**
 * The pre-playback preview: pre-generated poster frame + `viewer.playButton` over the black
 * canvas. APP-379: immersive — a tap toggles a **temporary** top bar (back + ⋯ file overflow),
 * never the photo-viewer's permanent Unhide/Delete/Share bar.
 *
 * APP-419 (P0-A/P0-B): the poster is now the **cached, pre-generated encrypted thumbnail** served
 * by [VaultThumbnailPipeline] — the exact same LRU-backed pipeline every grid/pager tile uses.
 * Two bugs this closes, both the "3rd occurrence" of the on-demand-decode mistake:
 *  - P0-A caching: revisiting a video (A→B→A) is an LRU **cache hit — zero re-decrypt**, where the
 *    old path re-ran a full `MediaMetadataRetriever` frame extraction on every recomposition.
 *  - P0-B pre-generation: the frame is the ~200px thumb written **once at hide-time**, so a large
 *    video no longer has to be stream-decrypted-and-frame-extracted at browse time (the source of
 *    the large-video "no thumbnail" failures). If a pre-APP-244 video has no stored thumb the
 *    pipeline backfills one exactly once, then serves from the cache forever after.
 */
@Composable
private fun VideoPreviewPage(
    item: VaultItem,
    hasVideoFrame: Boolean,
    onPlay: () -> Unit,
    fileActions: ViewerFileActions,
) {
    val context = LocalContext.current
    val repository = VaultGraph.contentRepository
    var barsVisible by remember(item.id) { mutableStateOf(true) }
    // Poster frame from the cached pipeline (APP-419) — never an on-demand full-video decode.
    // Audio blobs (hasVideoFrame == false) simply have no frame; the play button is the preview.
    val frame by produceState<ImageBitmap?>(initialValue = null, item.id, hasVideoFrame) {
        value = if (!hasVideoFrame) null else VaultThumbnailPipeline.load(context, item, repository)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { barsVisible = !barsVisible }) },
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
        // APP-379: immersive temporary top bar — back + ⋯ file overflow, no permanent bars.
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ImmersivePlayerTopBar(fileActions = fileActions)
        }
    }
}

/**
 * APP-379 — the immersive player's **temporary** top bar for a video/audio page: a back
 * button (start) and a ⋯ overflow (end) holding the vault file actions. It replaces the
 * photo-viewer's permanent Unhide/Delete/Move/Share bottom bar on a playable page, so the
 * player is edge-to-edge and the file actions stay reachable but out of the way (spec §5c).
 */
@Composable
private fun ImmersivePlayerTopBar(
    fileActions: ViewerFileActions,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(ViewerScrim, Color.Transparent)))
                .padding(horizontal = VaultTheme.spacing.sm, vertical = VaultTheme.spacing.sm),
    ) {
        IconButton(onClick = fileActions.onBack) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = ViewerOnCanvas)
        }
        Box(modifier = Modifier.weight(1f))
        FileActionsOverflow(fileActions = fileActions, tint = ViewerOnCanvas)
    }
}

/**
 * APP-379 — the ⋯ overflow button + menu carrying the vault file actions for a video/audio
 * page (Info/Share/Move/Unhide/Delete). Shared by the preview's [ImmersivePlayerTopBar] and
 * the playing overlay, so the file actions are surfaced identically in both states.
 */
@Composable
internal fun FileActionsOverflow(
    fileActions: ViewerFileActions,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "File actions", tint = tint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FileActionMenuItems(fileActions = fileActions, closeMenu = { expanded = false })
        }
    }
}

/**
 * APP-379 — the five vault file-action rows (Info/Share/Move/Unhide/Delete) emitted inside a
 * [DropdownMenu]. Shared so the preview's [FileActionsOverflow] and the playing player's ⋯
 * menu ([VideoPlayerControlsOverlay]) surface exactly the same actions. [closeMenu] dismisses
 * the host menu before the action fires.
 */
@Composable
internal fun FileActionMenuItems(
    fileActions: ViewerFileActions,
    closeMenu: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Info") },
        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
        onClick = {
            closeMenu()
            fileActions.onInfo()
        },
    )
    DropdownMenuItem(
        text = { Text("Share") },
        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
        onClick = {
            closeMenu()
            fileActions.onShare()
        },
    )
    DropdownMenuItem(
        text = { Text("Move") },
        leadingIcon = { Icon(VaultActionIcons.MoveTo, contentDescription = null) },
        onClick = {
            closeMenu()
            fileActions.onMove()
        },
    )
    DropdownMenuItem(
        text = { Text("Unhide") },
        leadingIcon = { Icon(VaultActionIcons.Unhide, contentDescription = null) },
        onClick = {
            closeMenu()
            fileActions.onUnhide()
        },
    )
    DropdownMenuItem(
        text = { Text("Delete") },
        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        onClick = {
            closeMenu()
            fileActions.onDelete()
        },
    )
}

/**
 * Plays [itemId]'s video/audio by **streaming the encrypted blob** through the seekable
 * decrypting [EncryptedVaultDataSource] (APP-347 / spec §7–§8): decrypt happens on
 * ExoPlayer's loader thread, one 512 KiB chunk at a time, so a large video scrubs/seeks
 * smoothly and **no plaintext ever touches disk** (§1.1/§1.2/§1.3). An undecodable
 * container/codec surfaces as a graceful message (§6) — never a crash. The player is
 * released when the page leaves composition.
 *
 * **APP-371 (F2/F3) — [playlist]:** Next/Prev and auto-advance are pager switches routed
 * through [PlaylistEngine]; REPEAT_CURRENT loops in place via `repeatMode` (so it never
 * reaches `STATE_ENDED`), every other mode advances on `STATE_ENDED` via
 * [VideoPlaylistController.onCompleted].
 *
 * **APP-371 (F4) — external subtitles:** a device (SAF) or vault-hidden `.srt/.ass` sub is
 * merged in via [buildMediaSource]'s [MergingMediaSource] + [SingleSampleMediaSource] (the
 * APP-370-mandated path). A vault-hidden sub streams through [EncryptedVaultDataSource] — no
 * plaintext temp; a device sub is read in place from its `content://` uri — no copy.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaPlayerPage(
    itemId: String,
    fileActions: ViewerFileActions,
    playlist: VideoPlaylistController,
) {
    val context = LocalContext.current
    val repository = VaultGraph.contentRepository
    var playbackError by remember(itemId) { mutableStateOf<PlaybackException?>(null) }

    // The side-loaded external subtitle bound to THIS video (F4). Null = none/embedded.
    var subtitle by remember(itemId) { mutableStateOf<LoadedSubtitle?>(null) }
    // The player listener is created once (remember); read the latest controller through this
    // so a mid-session order-mode change is honoured by the auto-advance (F3).
    val currentPlaylist by rememberUpdatedState(playlist)

    // Build the (optionally subtitle-merged) media source for this video. APP-370 contract:
    // NEVER MediaItem.setSubtitleConfigurations on the progressive source (that would stage a
    // plaintext temp for a vault-hidden sub) — always an explicit merged SingleSampleMediaSource.
    fun buildMediaSource(sub: LoadedSubtitle?): MediaSource {
        val vaultFactory = EncryptedVaultDataSource.Factory { id -> repository.openBlobReader(id) }
        val videoSource =
            ProgressiveMediaSource
                .Factory(vaultFactory)
                .createMediaSource(MediaItem.fromUri(EncryptedVaultDataSource.vaultMediaUri(itemId)))
        if (sub == null) return videoSource
        val subConfig =
            MediaItem.SubtitleConfiguration
                .Builder(Uri.parse(sub.uri))
                .setMimeType(sub.mimeType)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        // Vault-hidden sub → streamed decrypt (no plaintext temp); device sub → in-place read.
        val subFactory: DataSource.Factory =
            if (sub.fromVault) vaultFactory else DefaultDataSource.Factory(context)
        val subSource =
            SingleSampleMediaSource
                .Factory(subFactory)
                .createMediaSource(subConfig, C.TIME_UNSET)
        return MergingMediaSource(videoSource, subSource)
    }

    // Wave 4 (APP-351): while the player floats as a mini window it is owned by the
    // activity-scoped [MiniPlayerSession]. On Expand this page adopts the session's *live*
    // player (same instance — no re-decrypt from zero, APP-374 #6); otherwise it builds its own
    // per-page player exactly as before.
    val session = rememberMiniPlayerSession()
    val player =
        remember(itemId) {
            session.consumePlayerForExpand(itemId)
                ?: ExoPlayer.Builder(context, legacySubtitleRenderersFactory(context)).build().apply {
                    setMediaSource(buildMediaSource(null))
                    prepare()
                    playWhenReady = true
                }
        }

    // The viewer's auto-advance + error listener, added for BOTH created and adopted players and
    // removed on dispose — so a player handed off to the mini session never keeps this (soon
    // disposed) pager's listener; the session installs its own for mini-mode auto-advance.
    val viewerListener =
        remember(itemId) {
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = error
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // §5d auto-advance: the current video finished on its own → the order mode
                    // decides where playback goes (or to stop).
                    if (playbackState == Player.STATE_ENDED) currentPlaylist.onCompleted()
                }
            }
        }
    DisposableEffect(itemId, player) {
        player.addListener(viewerListener)
        onDispose {
            player.removeListener(viewerListener)
            // Never release a player the mini session has adopted (minimize handoff) — the
            // session owns its lifecycle then. Release only a page-owned player.
            if (!session.owns(player)) player.release()
        }
    }

    // APP-381 #1 · restore the playhead + play/pause after an activity recreation (forced
    // recreate / process-death). Real device rotation is already seamless via MainActivity's
    // android:configChanges — this is the safety net for a genuine teardown+rebuild.
    RetainedPlaybackEffect(player = player, itemId = itemId)

    // F3 · REPEAT_CURRENT loops the single item in place (never reaches STATE_ENDED); every
    // other mode advances across pages, so the player itself must not auto-repeat.
    LaunchedEffect(playlist.orderMode) {
        player.repeatMode =
            if (playlist.orderMode == OrderMode.REPEAT_CURRENT) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
    }

    // F4 · rebuild the source when a subtitle is loaded/removed, keeping playhead + play state.
    // The initial (null) source is already on the player from construction — skip that pass.
    var subtitleInitialised by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(subtitle) {
        if (!subtitleInitialised) {
            subtitleInitialised = true
            return@LaunchedEffect
        }
        val resumePosition = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaSource(buildMediaSource(subtitle))
        player.prepare()
        player.seekTo(resumePosition)
        player.playWhenReady = wasPlaying
    }

    // F4 · device (SAF) subtitle pick — a plain content:// read, no copy. The extension drives
    // the sample MIME (SubtitleFormats); a persistable grant is best-effort (single-sample
    // loads eagerly, so the transient session grant already suffices).
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = subtitleDisplayName(context, uri)
                val mime = SubtitleFormats.mimeTypeForName(name) ?: SubtitleFormats.MIME_SUBRIP
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                subtitle = LoadedSubtitle(uri.toString(), mime, name, fromVault = false)
            }
        }

    // F4 · vault-hidden subtitle pick — the vault's own .srt/.ass items, streamed decrypt.
    var showVaultSubPicker by remember(itemId) { mutableStateOf(false) }
    val vaultSubtitles by produceState(initialValue = emptyList<VaultItem>(), itemId) {
        repository.allItems().collect { all ->
            value = all.filter { SubtitleFormats.isSubtitle(it.originalName) }
        }
    }

    // APP-419 (P1): the decoded scrub-preview storyboard for THIS video, loaded once (LRU-backed)
    // so a seekbar drag can crop frames from it in-memory. Null when the item has no strip (audio,
    // pre-APP-419 item, extract failure) — the seekbar then shows only its time-code bubble.
    val scrubPreview by produceState<VideoStoryboard.Strip?>(initialValue = null, itemId) {
        value = runCatching { VideoStoryboardCache.load(itemId, repository) }.getOrNull()
    }

    // ---- Wave 3 (APP-350) state hoisted here so all three layers share it ----
    var controlsVisible by remember(itemId) { mutableStateOf(true) }
    var locked by remember(itemId) { mutableStateOf(false) }
    var speed by remember(itemId) { mutableFloatStateOf(PlaybackSpeeds.DEFAULT) }
    var muted by remember(itemId) { mutableStateOf(false) }
    var aspectMode by remember(itemId) { mutableStateOf(VideoScaleMath.AspectMode.FIT) }
    var rotationDegrees by remember(itemId) { mutableIntStateOf(0) }
    var scale by remember(itemId) { mutableFloatStateOf(VideoZoomMath.MIN_SCALE) }
    var panX by remember(itemId) { mutableFloatStateOf(0f) }
    var panY by remember(itemId) { mutableFloatStateOf(0f) }
    // APP-381 #4 · Full Screen — hides the system status/navigation bars for a larger viewing
    // area (design-reference "Full Screen"). Survives a config change/recreate (rememberSaveable).
    var fullscreen by rememberSaveable(itemId) { mutableStateOf(false) }

    // Propagate speed + mute to ExoPlayer whenever they change.
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    // APP-381 #4 · apply the Full Screen system-bar state to the host window, and ALWAYS restore
    // the bars when the player leaves composition so the calculator / grid is never stuck
    // immersive. The bars re-hide on any config change because this effect re-runs with the
    // retained [fullscreen] flag.
    val activity = remember(context) { context.findActivityOrNull() }
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat
                    .getInsetsController(w, w.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val resizeMode = when (aspectMode) {
        VideoScaleMath.AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMath.AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMath.AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMath.AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    val error = playbackError
    if (error != null) {
        UnsupportedMediaPage(error)
    } else {
        // Three-layer stack (spec §5c / §6):
        //  1. VideoPlayerSurface — the video render + Wave-2 gestures + Wave-3 pinch-zoom
        //  2. VideoPlayerControlsOverlay — scrim / quick-row / menus / dialogs / sheets
        //  3. VideoPlayerLockOverlay (top-most, only when locked) — intercepts ALL pointers
        //     before layers 1 and 2 see them so controls AND gestures are fully disabled
        Box(modifier = Modifier.fillMaxSize()) {
            VideoPlayerSurface(
                player = player,
                onToggleControls = { controlsVisible = !controlsVisible },
                locked = locked,
                scale = scale,
                panX = panX,
                panY = panY,
                onPinch = { ns, nx, ny ->
                    scale = ns
                    panX = nx
                    panY = ny
                },
                resizeMode = resizeMode,
                rotationDegrees = rotationDegrees,
            )
            VideoPlayerControlsOverlay(
                player = player,
                controlsVisible = controlsVisible,
                onToggleControls = { controlsVisible = !controlsVisible },
                // APP-379: back + the vault file actions (⋯ overflow) live in the player's
                // temporary top bar — never a permanent file-management bar over the video.
                fileActions = fileActions,
                locked = locked,
                onLockChanged = { locked = it },
                speed = speed,
                onSpeedChanged = { speed = it },
                aspectMode = aspectMode,
                onAspectModeChanged = { aspectMode = it },
                rotationDegrees = rotationDegrees,
                onRotationChanged = { rotationDegrees = it },
                muted = muted,
                onMutedChanged = { muted = it },
                fullscreen = fullscreen,
                onFullscreenChanged = { fullscreen = it },
                playlist = playlist,
                currentSubtitleLabel = subtitle?.label,
                onLoadDeviceSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                onLoadVaultSubtitle = { showVaultSubPicker = true },
                onClearSubtitle = { subtitle = null },
                // §5c Mini Player (APP-351): hand the live player to the activity-scoped session
                // and snapshot the current-folder video/audio playlist for the mini Next/Prev.
                // VaultNavHost observes mode == MINI and pops back to the vault (Option A).
                onMinimize = {
                    val videos =
                        playlist.items.filter {
                            it.category == VaultCategory.VIDEOS || it.category == VaultCategory.AUDIOS
                        }
                    val current = playlist.items.firstOrNull { it.id == itemId }
                    val videoIds = videos.map { it.id }
                    session.minimize(
                        exoPlayer = player,
                        itemId = itemId,
                        category = current?.category ?: VaultCategory.VIDEOS,
                        folderId = current?.folderId,
                        playlistVideoIds = videoIds,
                        currentIndex = videoIds.indexOf(itemId).coerceAtLeast(0),
                        order = playlist.orderMode,
                    )
                },
                // APP-388 #2: playlist rows reuse the folder-grid encrypted-thumbnail pipeline.
                loadThumbnail = { item -> VaultThumbnailPipeline.load(context, item, repository) },
                // APP-419 (P1): the current video's scrub-preview storyboard for live seek preview.
                scrubPreview = scrubPreview,
            )
            if (locked) {
                VideoPlayerLockOverlay(onUnlock = { locked = false })
            }
        }
    }

    // F4 · the vault subtitle picker dialog (its own .srt/.ass items).
    if (showVaultSubPicker) {
        AlertDialog(
            onDismissRequest = { showVaultSubPicker = false },
            title = { Text("Vault subtitles") },
            text = {
                if (vaultSubtitles.isEmpty()) {
                    Text("No .srt/.ass subtitles hidden in the vault")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(vaultSubtitles) { sub ->
                            ListItem(
                                headlineContent = { Text(sub.originalName, maxLines = 1) },
                                modifier =
                                    Modifier.clickable {
                                        val mime = SubtitleFormats.mimeTypeForName(sub.originalName)
                                            ?: SubtitleFormats.MIME_SUBRIP
                                        subtitle =
                                            LoadedSubtitle(
                                                uri = EncryptedVaultDataSource.vaultMediaUri(sub.id).toString(),
                                                mimeType = mime,
                                                label = sub.originalName,
                                                fromVault = true,
                                            )
                                        showVaultSubPicker = false
                                    },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVaultSubPicker = false }) { Text("Close") }
            },
        )
    }
}

/**
 * A renderers factory that keeps **legacy (render-time) subtitle decoding** enabled (APP-371
 * F4). Media3 1.4 parses subtitles during extraction by default and the TextRenderer then only
 * accepts pre-parsed `application/x-media3-cues`; a **sideloaded** [SingleSampleMediaSource]
 * subtitle emits raw `application/x-subrip`/SSA samples (it has no during-extraction parser), so
 * without this the merged subtitle throws `Legacy decoding is disabled`. Enabling legacy
 * decoding restores render-time parsing — the only lever, since the APP-370 contract forces a
 * hand-built subtitle source (its DataSource must be [EncryptedVaultDataSource] for a vault sub).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun legacySubtitleRenderersFactory(context: Context): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildTextRenderers(
            context: Context,
            output: TextOutput,
            outputLooper: Looper,
            extensionRendererMode: Int,
            out: ArrayList<Renderer>,
        ) {
            out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
        }
    }

/** Query a picked subtitle's display name (for the MIME map + the menu label). */
private fun subtitleDisplayName(
    context: Context,
    uri: Uri,
): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
    return uri.lastPathSegment ?: "subtitle"
}

/**
 * Graceful playback-failure page (§6): a decode/format error reads as "format isn't
 * supported"; anything else (vault I/O, missing key) as a generic couldn't-play message —
 * never a crash, never a silent black screen.
 */
@Composable
private fun UnsupportedMediaPage(error: PlaybackException) {
    val isFormatIssue =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> true
            else -> false
        }
    val message =
        if (isFormatIssue) "This format isn't supported" else "Couldn't play this file"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VaultTheme.spacing.md),
        modifier = Modifier.fillMaxSize().padding(VaultTheme.spacing.lg),
    ) {
        Box(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = ViewerOnCanvas,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = message,
            color = ViewerOnCanvas,
            textAlign = TextAlign.Center,
        )
        Box(modifier = Modifier.weight(1f))
    }
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
    onShare: () -> Unit,
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
        // Share: decrypt to a scoped temp copy, FileProvider-serve, purge on return (APP-294).
        ViewerAction(label = "Share", tint = ViewerOnCanvas, icon = Icons.Filled.Share, onClick = onShare)
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

// Retained decoded bitmaps for instant swipe-back (APP-299 P0-1). Kept small: a decoded
// full-res photo is heavy, and `beyondViewportPageCount = 1` already keeps the neighbours
// composed, so this mainly covers stepping back a page or two.
private const val BITMAP_CACHE_MAX = 4

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

// A ~2× step (the Samsung/Oppo gallery feel), not a jump to max, so a double-tap inspects
// detail while a second tap returns to fit (APP-299 P2-4).
private const val DOUBLE_TAP_ZOOM = 2f
private const val DOUBLE_TAP_ANIM_MS = 220

/**
 * Pure zoom/pan geometry for the viewer (APP-299 P2-4), split out so the double-tap
 * centring and the pan-bounds contract are unit-testable without a device. All values are
 * in the container's pixel space; translation is measured from the centre (graphicsLayer's
 * default transform origin).
 */
internal object ViewerZoomMath {
    /**
     * On-screen size of a [contentW]×[contentH] image drawn `ContentScale.Fit` inside a
     * [containerW]×[containerH] box at scale 1. A 90°/270° [rotationDegrees] swaps the
     * image's effective aspect (the rotated bitmap is what must fit), so pan bounds stay
     * correct for a rotated photo. Degenerate inputs fall back to the container size.
     */
    fun fittedContentSize(
        containerW: Float,
        containerH: Float,
        contentW: Float,
        contentH: Float,
        rotationDegrees: Int,
    ): Size {
        if (containerW <= 0f || containerH <= 0f || contentW <= 0f || contentH <= 0f) {
            return Size(containerW, containerH)
        }
        val quarter = ((rotationDegrees % 360) + 360) % 360
        val rotated = quarter == 90 || quarter == 270
        val cw = if (rotated) contentH else contentW
        val ch = if (rotated) contentW else contentH
        val fit = minOf(containerW / cw, containerH / ch)
        return Size(cw * fit, ch * fit)
    }

    /**
     * Max |translation| per axis (from centre) that keeps a [fitted] image scaled by [scale]
     * from being dragged past its own edge into the container's empty margin — every corner
     * reachable, no edge overscroll. 0 on an axis the scaled image doesn't overflow (e.g. a
     * letterboxed dimension at low zoom).
     */
    fun maxPan(
        containerW: Float,
        containerH: Float,
        fitted: Size,
        scale: Float,
    ): Offset =
        Offset(
            ((fitted.width * scale - containerW) / 2f).coerceAtLeast(0f),
            ((fitted.height * scale - containerH) / 2f).coerceAtLeast(0f),
        )

    /**
     * Translation (from centre) that keeps the screen point [tap] stationary when zooming to
     * [scale]. Derivation: a point maps to `C + scale·(p − C) + t`; holding the tapped point
     * fixed ⇒ `t = (C − tap)·(scale − 1)`. The prior code used `·scale`, which over-shot the
     * centring — the concrete bug behind "not centred on the tap".
     */
    fun focusOffset(
        tap: Offset,
        containerW: Float,
        containerH: Float,
        scale: Float,
    ): Offset {
        val center = Offset(containerW / 2f, containerH / 2f)
        return (center - tap) * (scale - 1f)
    }

    /** Clamp [candidate] to ±[max] per axis. */
    fun clamp(
        candidate: Offset,
        max: Offset,
    ): Offset = Offset(candidate.x.coerceIn(-max.x, max.x), candidate.y.coerceIn(-max.y, max.y))
}

// Viewer chrome colors (design §3 VaultViewerTokens): a true-black canvas so the photo is
// the hero, on-canvas glyphs/text in white, and a 50%-ink scrim behind the floating bars.
private val ViewerCanvas = Color(0xFF000000)
private val ViewerOnCanvas = Color(0xFFFFFFFF)
private val ViewerOnCanvasMuted = Color(0xB3FFFFFF)
private val ViewerScrim = Color(0x80000000)

/**
 * The one [MiniPlayerSession] for the whole vault, scoped to the hosting [ComponentActivity]'s
 * `ViewModelStore` (APP-374 #1: activity-scoped, never a `@Singleton`/`object`). Every in-vault
 * destination — the viewer and the `VaultNavHost` overlay — resolves the *same* instance, so the
 * mini player survives the `VIEWER → vault` nav transition while dying with the Activity window.
 */
@Composable
private fun rememberMiniPlayerSession(): MiniPlayerSession {
    val context = LocalContext.current
    val activity =
        remember(context) { context.findComponentActivity() }
            ?: error("MiniPlayerSession requires a ComponentActivity host")
    return viewModel(viewModelStoreOwner = activity)
}

/** Walk the [Context] wrapper chain to the hosting [ComponentActivity] (the vault window). */
private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
