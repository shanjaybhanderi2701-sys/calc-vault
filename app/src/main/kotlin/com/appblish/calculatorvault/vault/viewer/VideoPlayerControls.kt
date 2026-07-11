package com.appblish.calculatorvault.vault.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import com.appblish.calculatorvault.vault.model.VaultItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ControlScrim = Color(0xCC000000)
private val BottomGradient = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0xCC000000)),
)
private val TopGradient = Brush.verticalGradient(
    colors = listOf(Color(0xCC000000), Color.Transparent),
)
private const val AUTO_HIDE_MS = 3_500L
private const val UNLOCK_PILL_HIDE_MS = 2_000L

// APP-388 #1 · thin seekbar dimensions/colors. A 2dp line with a 12dp round thumb replaces
// the default Material Slider (≈4dp track / ≈20dp pill). Colors kept from the prior white bar.
// APP-395 (round 4): the Material3 Slider slot API positioned the thumb and the custom track with
// two independent vertical-offset computations, which drifted off each other on-device (owner: "seek
// bar thumb not centred"). Replaced by [ThinSeekbar] — both the track and the thumb are laid out in
// one Box and anchored to the SAME vertical centre line, so they can never diverge.
private val SEEK_TRACK_HEIGHT = 2.dp
private val SEEK_THUMB_DIAMETER = 12.dp

// The invisible grab/touch area around the thin visuals — thumb + track are centred within it.
private val SEEK_TOUCH_HEIGHT = 24.dp
private val SeekActiveColor = Color.White
private val SeekInactiveColor = Color(0x66FFFFFF)

// APP-395 · test tags so an instrumented layout assertion can prove the thumb's vertical centre
// matches the track's vertical centre (see SeekbarThumbCenteringDoDTest).
internal const val SEEK_THUMB_TAG = "seek_thumb"
internal const val SEEK_TRACK_TAG = "seek_track"

/**
 * APP-395 (round 4) · the thin video seekbar. Replaces the Material3 [androidx.compose.material3.Slider]
 * whose slot API computed the thumb offset and the custom-track offset independently, letting the round
 * thumb drift off the 2dp track vertically on-device.
 *
 * Centring guarantee: the track and the thumb live in ONE [Box] and are both anchored with a
 * `CenterStart`/`Center` alignment, i.e. to the *same* vertical centre line of that box. There is no
 * second, independent vertical-offset computation that could diverge — so the thumb's centre sits
 * exactly on the track's centre at every [fraction] (start / mid / end) and while dragging. A layout
 * assertion in `SeekbarThumbCenteringDoDTest` locks this to ±1px.
 *
 * @param fraction current progress in `0f..1f`.
 * @param onScrub called with the touched fraction on tap and continuously during a drag.
 * @param onScrubFinished called once the gesture ends (parent commits the latched scrub position).
 */
@Composable
internal fun ThinSeekbar(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = modifier.height(SEEK_TOUCH_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val thumbPx = with(density) { SEEK_THUMB_DIAMETER.toPx() }
        // The thumb centre travels between thumbPx/2 (fraction 0) and widthPx - thumbPx/2 (fraction 1);
        // the same inset is applied to the track so the active-fill edge always meets the thumb centre.
        val travelPx = (widthPx - thumbPx).coerceAtLeast(0f)
        val thumbInset = SEEK_THUMB_DIAMETER / 2

        fun xToFraction(x: Float): Float = if (travelPx <= 0f) 0f else ((x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)

        // ---- Track (inactive + active), vertically centred within the touch area ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = thumbInset)
                .height(SEEK_TRACK_HEIGHT)
                .align(Alignment.CenterStart)
                .testTag(SEEK_TRACK_TAG),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SEEK_TRACK_HEIGHT)
                    .clip(CircleShape)
                    .background(SeekInactiveColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(SEEK_TRACK_HEIGHT)
                    .clip(CircleShape)
                    .background(SeekActiveColor),
            )
        }

        // ---- Thumb: same box, same centre line, only translated horizontally by the fraction ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(x = (travelPx * clamped).roundToInt(), y = 0) }
                .size(SEEK_THUMB_DIAMETER)
                .clip(CircleShape)
                .background(SeekActiveColor)
                .testTag(SEEK_THUMB_TAG),
        )

        // ---- Gesture layer covering the whole touch area (tap-to-seek + press-drag-to-scrub) ----
        // APP-398 (round 5b): one deterministic gesture loop handles BOTH tap and drag, and it runs on
        // the **Initial** pointer pass so it pre-empts every ancestor gesture.
        //
        // Round-5a (Main-pass consume) still failed on-device with a *regression*: dragging the seekbar
        // shifted the whole screen. Root cause — the video page is a child of the viewer's
        // [androidx.compose.foundation.pager.HorizontalPager] (userScrollEnabled while un-zoomed) and the
        // pager's horizontal-scroll detector is an *ancestor*. A Main-pass consume happens too late in
        // practice: the pager claimed the horizontal drag first and paged the whole layout, so the thumb
        // never grabbed. Consuming on the Initial pass (root→leaf) marks every event handled *before* the
        // pager's and the video-body's Main-pass detectors ever see it, so neither can start a drag that
        // began inside the seekbar. This is the "seekbar consumes its own drag so the two don't fight"
        // guarantee from the DoD, and it kills the page-shift regression.
        //
        // Behaviour: the thumb latches to the finger on the DOWN event (no slop gate — YouTube/MX
        // "press = grab") and follows it continuously; a down+up with no movement is just a tap-to-jump.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(travelPx, thumbPx) {
                    awaitEachGesture {
                        // Initial pass: seize the gesture before the pager / body-swipe detectors.
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        // Grab immediately: thumb + live time jump to the touch point.
                        onScrub(xToFraction(down.position.x))
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (!change.pressed) {
                                // Consume the up too, so no ancestor reads a fling off the release.
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                onScrub(xToFraction(change.position.x))
                            }
                            change.consume()
                        }
                        // Release (or cancel): parent commits the latched scrub position via seekTo.
                        onScrubFinished()
                    }
                },
        )
    }
}

/**
 * CalcVault Phase B · Wave 3 · APP-350, MX-Player redesign · APP-384 — the controls overlay that
 * sits above [VideoPlayerSurface] (spec §5c / §6). MX-Player layout:
 *  - a single large **center play/pause** (the only center affordance — no prev/rewind cluster;
 *    the built-in PlayerView controller is fully off, APP-384 #2),
 *  - a clean **bottom bar**: a full-width seekbar then a tidy control row grouped into transport
 *    (prev · play/pause · next) and tools (lock · rotate · aspect · speed · full screen · mute),
 *  - a **top bar**: back (start) and mini-player · playlist · speed-badge · ⋯ (end),
 *  - the ⋯ menu split into **Playback settings** and a de-emphasized **File actions** group (#1).
 *
 * Auto-hides after [AUTO_HIDE_MS] ms of inactivity; any interaction resets the timer
 * (resetAutoHide increments the LaunchedEffect key). While **locked** (#5) NONE of this chrome
 * renders (`chromeVisible = controlsVisible && !locked`).
 *
 * The **lock overlay** ([VideoPlayerLockOverlay]) is a separate top-most composable — it must
 * intercept all pointer events *before* [VideoPlayerSurface]'s gesture layer sees them, so it
 * lives above this composable in the caller's Box stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerControlsOverlay(
    player: Player,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    // APP-379: the vault file actions (back + Info/Share/Move/Unhide/Delete). Back is a
    // top-start button; the rest live in the ⋯ overflow — the immersive player never wears a
    // permanent Share/Delete/Unhide bar.
    fileActions: ViewerFileActions,
    locked: Boolean,
    onLockChanged: (Boolean) -> Unit,
    speed: Float,
    onSpeedChanged: (Float) -> Unit,
    aspectMode: VideoScaleMath.AspectMode,
    onAspectModeChanged: (VideoScaleMath.AspectMode) -> Unit,
    rotationDegrees: Int,
    onRotationChanged: (Int) -> Unit,
    muted: Boolean,
    onMutedChanged: (Boolean) -> Unit,
    // APP-381 #4: Full Screen — hide/show the system bars for a larger viewing area (design ref).
    fullscreen: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    // APP-371 F1–F3: the §5d playlist (current-folder videos, order modes, Next/Prev/tap-switch).
    playlist: VideoPlaylistController,
    // APP-371 F4: side-loaded external subtitle state + loaders (device SAF / vault-hidden).
    currentSubtitleLabel: String?,
    onLoadDeviceSubtitle: () -> Unit,
    onLoadVaultSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    // APP-351 (Wave 4): minimize into the in-app floating mini player (§5c).
    onMinimize: () -> Unit,
    // APP-388 #2: per-row playlist thumbnails via the folder-grid encrypted-thumbnail pipeline
    // (VaultThumbnailPipeline). Null → rows fall back to a plain glyph (e.g. previews/tests).
    loadThumbnail: (suspend (VaultItem) -> ImageBitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    // Auto-hide: bump nonce to restart the 3.5-second idle timer.
    var autoHideNonce by remember { mutableIntStateOf(0) }

    fun resetAutoHide() {
        autoHideNonce++
    }

    LaunchedEffect(autoHideNonce, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        onToggleControls()
    }

    // Track ExoPlayer playing state for the center play/pause affordance.
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Track available embedded subtitle + audio tracks for the ⋯ submenus.
    var tracks by remember { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Local UI state for menus / dialogs / sheets.
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var subtitlesMenuExpanded by remember { mutableStateOf(false) }
    var audioMenuExpanded by remember { mutableStateOf(false) }
    var aspectMenuExpanded by remember { mutableStateOf(false) }
    var speedDialogVisible by remember { mutableStateOf(false) }
    var playlistSheetVisible by remember { mutableStateOf(false) }

    // APP-384 · MX-Player bottom seekbar: poll the playhead while controls are visible so the
    // full-width Slider tracks playback. Dragging the thumb latches [scrubbing] so the poll
    // doesn't fight the finger; release commits the seek.
    var positionMs by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var durationMs by remember { mutableLongStateOf(player.positiveDurationMs()) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValueMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player, controlsVisible) {
        while (controlsVisible) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.positiveDurationMs()
            }
            delay(500)
        }
    }

    // APP-384 #5 — Full immersive lock: while locked, NONE of the player chrome renders (just the
    // video + the lock overlay's unlock affordance), so nothing can be triggered accidentally.
    val chromeVisible = controlsVisible && !locked

    Box(modifier = modifier.fillMaxSize()) {
        // Bottom scrim gradient (sits behind quick-row).
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(BottomGradient),
            )
        }

        // Top scrim gradient (sits behind ⋯ menu / speed badge).
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(TopGradient),
            )
        }

        // ---- Top-left: back (exit the immersive player, APP-379) ----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IconButton(
                onClick = {
                    fileActions.onBack()
                    resetAutoHide()
                },
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }

        // ---- APP-384 · MX-Player bottom bar: full-width seekbar, then a grouped control row ----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
            ) {
                // Full-width seekbar: elapsed / [========o----] / total.
                val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
                val sliderValue = (if (scrubbing) scrubValueMs else positionMs.toFloat())
                    .coerceIn(0f, sliderMax)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // APP-398 (round 5b) · no-layout-shift: the elapsed label grows as the scrub value
                    // climbs (0:05 → 1:23:45), which would reflow this Row and slide the seekbar under
                    // the finger mid-drag. Pin the elapsed slot to the (never-shorter) duration string —
                    // an invisible sizer reserves that width so the seekbar's origin is rock-steady while
                    // dragging.
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = VideoGestureMath.formatTime(durationMs),
                            color = Color.Transparent,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = VideoGestureMath.formatTime(sliderValue.toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    // APP-388 #1 / APP-395 #1 · thin seekbar: a 2dp progress line with a small round
                    // thumb (owner reference). [ThinSeekbar] anchors the track and thumb to one shared
                    // vertical centre, so the thumb stays centred on the track at every position and
                    // during an active drag (round-4 fix).
                    val trackFraction = (sliderValue / sliderMax).coerceIn(0f, 1f)
                    ThinSeekbar(
                        fraction = trackFraction,
                        onScrub = { f ->
                            scrubbing = true
                            scrubValueMs = f * sliderMax
                            resetAutoHide()
                        },
                        onScrubFinished = {
                            player.seekTo(scrubValueMs.toLong())
                            positionMs = scrubValueMs.toLong()
                            scrubbing = false
                            resetAutoHide()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(
                        text = VideoGestureMath.formatTime(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                // Control row — two logical groups: transport (left), display/tools (right).
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Transport group: previous · play/pause · next.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            // §5d Previous: PlaylistEngine.manualPrev (always wraps) → pager switch.
                            playlist.onPrevious()
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.SkipPrevious, "Previous", tint = Color.White)
                        }
                        IconButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            resetAutoHide()
                        }) {
                            Icon(
                                imageVector = if (isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = {
                            // §5d Next: PlaylistEngine.manualNext (always wraps) → pager switch.
                            playlist.onNext()
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.SkipNext, "Next", tint = Color.White)
                        }
                    }
                    // Display/tools group: lock · rotate · aspect · speed · full screen · mute.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            // Lock: activates the full-immersive lock overlay above this composable.
                            onLockChanged(true)
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.Lock, "Lock controls", tint = Color.White)
                        }
                        IconButton(onClick = {
                            onRotationChanged(VideoScaleMath.nextRotation(rotationDegrees))
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.ScreenRotation, "Rotate", tint = Color.White)
                        }
                        IconButton(onClick = {
                            // Quick Fit ⇄ Fill toggle (§5c); full mode list lives in the ⋯ menu.
                            onAspectModeChanged(VideoScaleMath.nextDisplayMode(aspectMode))
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.AspectRatio, "Aspect ratio", tint = Color.White)
                        }
                        IconButton(onClick = {
                            speedDialogVisible = true
                            resetAutoHide()
                        }) {
                            Icon(VaultActionIcons.Speed, "Playback speed", tint = Color.White)
                        }
                        IconButton(onClick = {
                            // Full Screen: toggle the system bars for a larger viewing area
                            // (APP-381 #4, design-reference "Full Screen").
                            onFullscreenChanged(!fullscreen)
                            resetAutoHide()
                        }) {
                            Icon(
                                imageVector = VaultActionIcons.Fullscreen,
                                contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = {
                            onMutedChanged(!muted)
                            resetAutoHide()
                        }) {
                            Icon(
                                imageVector = if (muted) VaultActionIcons.VolumeOff else VaultActionIcons.VolumeOn,
                                contentDescription = if (muted) "Unmute" else "Mute",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // ---- Top-right: mini player · playlist · speed badge · ⋯ menu ----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            ) {
                // §5c Mini Player: minimize into the in-app floating window (APP-351 / Wave 4).
                IconButton(onClick = {
                    onMinimize()
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.PictureInPicture,
                        contentDescription = "Mini player",
                        tint = Color.White,
                    )
                }
                // §5d Playlist: current-folder videos + order modes (moved off the bottom bar).
                IconButton(onClick = {
                    playlistSheetVisible = true
                    resetAutoHide()
                }) {
                    Icon(
                        imageVector = VaultActionIcons.PlaylistPlay,
                        contentDescription = "Playlist",
                        tint = Color.White,
                    )
                }
                if (!PlaybackSpeeds.isDefault(speed)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ControlScrim)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = PlaybackSpeeds.label(speed),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box {
                    IconButton(onClick = {
                        moreMenuExpanded = true
                        resetAutoHide()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        // APP-384 #1(a) — PLAYBACK SETTINGS group (primary while watching).
                        MenuSectionHeader("Playback settings")
                        DropdownMenuItem(
                            text = { Text("Subtitles") },
                            leadingIcon = {
                                Icon(VaultActionIcons.Subtitles, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                subtitlesMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Audio track") },
                            leadingIcon = {
                                Icon(VaultActionIcons.VolumeOn, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                audioMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Aspect ratio") },
                            leadingIcon = {
                                Icon(VaultActionIcons.AspectRatio, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                aspectMenuExpanded = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Playback speed") },
                            leadingIcon = {
                                Icon(VaultActionIcons.Speed, contentDescription = null)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                speedDialogVisible = true
                            },
                        )
                        // APP-384 #1(b) — FILE ACTIONS group, visually separated and
                        // de-emphasized (secondary while watching). APP-379: reachable here in
                        // the temporary ⋯ overflow — never a permanent bar over the video.
                        HorizontalDivider()
                        MenuSectionHeader("File actions")
                        FileActionMenuItems(
                            fileActions = fileActions,
                            closeMenu = { moreMenuExpanded = false },
                        )
                    }
                    SubtitleTrackMenu(
                        expanded = subtitlesMenuExpanded,
                        tracks = tracks,
                        player = player,
                        currentSubtitleLabel = currentSubtitleLabel,
                        onLoadDeviceSubtitle = {
                            subtitlesMenuExpanded = false
                            onLoadDeviceSubtitle()
                        },
                        onLoadVaultSubtitle = {
                            subtitlesMenuExpanded = false
                            onLoadVaultSubtitle()
                        },
                        onClearSubtitle = {
                            subtitlesMenuExpanded = false
                            onClearSubtitle()
                        },
                        onDismiss = { subtitlesMenuExpanded = false },
                    )
                    AudioTrackMenu(
                        expanded = audioMenuExpanded,
                        tracks = tracks,
                        player = player,
                        onDismiss = { audioMenuExpanded = false },
                    )
                    AspectRatioMenu(
                        expanded = aspectMenuExpanded,
                        current = aspectMode,
                        onSelected = {
                            onAspectModeChanged(it)
                            aspectMenuExpanded = false
                        },
                        onDismiss = { aspectMenuExpanded = false },
                    )
                }
            }
        }

        // Center play/pause: shown when controls are visible (replaces built-in controller).
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(ControlScrim)
                    .clickable {
                        if (player.isPlaying) player.pause() else player.play()
                        resetAutoHide()
                    },
            ) {
                Icon(
                    imageVector = if (isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // ---- Speed dialog: segmented chip selector, one chip per PlaybackSpeeds.OPTIONS ----
        if (speedDialogVisible) {
            AlertDialog(
                onDismissRequest = { speedDialogVisible = false },
                title = { Text("Playback speed") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PlaybackSpeeds.OPTIONS.forEach { option ->
                            FilterChip(
                                selected = PlaybackSpeeds.nearest(speed) == option,
                                onClick = {
                                    onSpeedChanged(option)
                                    speedDialogVisible = false
                                    resetAutoHide()
                                },
                                label = { Text(PlaybackSpeeds.label(option)) },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { speedDialogVisible = false }) { Text("Close") }
                },
            )
        }

        // ---- Playlist bottom sheet (~70% height, over the paused video) ----
        if (playlistSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { playlistSheetVisible = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                Text(
                    text = "Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // F1 — the current-folder videos. Tap any row to switch playback to it (the
                // pager settles on it and its player takes over); the playing row is marked.
                Text(
                    text = "Videos (${playlist.items.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    itemsIndexed(playlist.items) { index, item ->
                        val isPlaying = index == playlist.currentIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = item.originalName,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            },
                            // APP-388 #2: show the item duration alongside the title.
                            supportingContent = {
                                if (item.durationMs > 0L) {
                                    Text(
                                        text = VideoGestureMath.formatTime(item.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            // APP-388 #2: per-row encrypted thumbnail (folder-grid pipeline) with a
                            // now-playing / play affordance overlaid; falls back to a glyph tile.
                            leadingContent = {
                                PlaylistRowThumbnail(
                                    item = item,
                                    isPlaying = isPlaying,
                                    loadThumbnail = loadThumbnail,
                                )
                            },
                            modifier = Modifier.clickable {
                                playlist.onSelect(index)
                                playlistSheetVisible = false
                                resetAutoHide()
                            },
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    text = "Order mode",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OrderMode.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        leadingContent = {
                            RadioButton(
                                selected = playlist.orderMode == mode,
                                onClick = { playlist.onOrderModeChanged(mode) },
                            )
                        },
                        modifier = Modifier.clickable { playlist.onOrderModeChanged(mode) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * APP-388 #2 — a playlist-row leading tile: the video's encrypted thumbnail (decoded via the
 * same [loadThumbnail] pipeline the folder grid uses), 16:9, with a small play / now-playing
 * badge overlaid. Falls back to a plain glyph tile when no thumbnail is available (or in
 * previews/tests where [loadThumbnail] is null).
 */
@Composable
private fun PlaylistRowThumbnail(
    item: VaultItem,
    isPlaying: Boolean,
    loadThumbnail: (suspend (VaultItem) -> ImageBitmap?)?,
) {
    val thumbnail: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = loadThumbnail?.invoke(item)
    }
    Box(
        modifier = Modifier
            .width(56.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(4.dp))
            .background(ControlScrim),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            imageVector = if (isPlaying) VaultActionIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Now playing" else null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Lock overlay (APP-384 #5) — a separate full-screen layer placed **above**
 * [VideoPlayerControlsOverlay] and [VideoPlayerSurface] in the caller's Box. While locked the
 * player chrome is already hidden (`chromeVisible == false`) and [VideoPlayerSurface]'s gesture
 * handler self-disables (`if (locked) return@pointerInput`), so there is nothing active beneath
 * to trigger accidentally — this overlay therefore does NOT need to consume every event (the old
 * Initial-pass "consume all" also swallowed the unlock pill's own taps, so the affordance never
 * unlocked).
 *
 * A **background tap layer** re-reveals the unlock pill for [UNLOCK_PILL_HIDE_MS] ms on any touch
 * on the bare video; it sits *below* the pill and does not consume, so the pill stays tappable.
 * Tapping the pill calls [onUnlock].
 */
@Composable
internal fun VideoPlayerLockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pillVisible by remember { mutableStateOf(true) }
    var touchNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(touchNonce) {
        delay(UNLOCK_PILL_HIDE_MS)
        pillVisible = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Background: any tap on the bare video re-reveals the unlock pill. Placed BELOW the pill
        // and non-consuming so the pill remains directly tappable; nothing active lies beneath.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pillVisible = true
                        touchNonce++
                    }
                },
        )
        AnimatedVisibility(
            visible = pillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TextButton(
                onClick = onUnlock,
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ControlScrim),
            ) {
                Icon(
                    imageVector = VaultActionIcons.LockOpen,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("Tap to unlock", color = Color.White)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitleTrackMenu(
    expanded: Boolean,
    tracks: Tracks,
    player: Player,
    currentSubtitleLabel: String?,
    onLoadDeviceSubtitle: () -> Unit,
    onLoadVaultSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // F4 — side-load an external subtitle. Both paths build the APP-370-mandated
        // MergingMediaSource + SingleSampleMediaSource (see MediaPlayerPage.buildMediaSource);
        // a vault-hidden sub streams through EncryptedVaultDataSource — no plaintext temp.
        DropdownMenuItem(
            text = { Text("Load from device…") },
            leadingIcon = { Icon(VaultActionIcons.MoveTo, contentDescription = null) },
            onClick = onLoadDeviceSubtitle,
        )
        DropdownMenuItem(
            text = { Text("Load from vault…") },
            leadingIcon = { Icon(VaultActionIcons.Unhide, contentDescription = null) },
            onClick = onLoadVaultSubtitle,
        )
        if (currentSubtitleLabel != null) {
            DropdownMenuItem(
                text = { Text("Remove: $currentSubtitleLabel") },
                onClick = onClearSubtitle,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Off") },
            onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setIgnoredTextSelectionFlags(
                        C.SELECTION_FLAG_DEFAULT or
                            C.SELECTION_FLAG_FORCED or
                            C.SELECTION_FLAG_AUTOSELECT,
                    ).build()
                onDismiss()
            },
        )
        if (textGroups.isEmpty()) {
            DropdownMenuItem(
                text = { Text("No embedded subtitles") },
                onClick = onDismiss,
                enabled = false,
            )
        } else {
            textGroups.forEachIndexed { i, group ->
                val lang = if (group.mediaTrackGroup.length > 0) {
                    group.mediaTrackGroup.getFormat(0).language
                } else {
                    null
                }
                val label = lang ?: "Track ${i + 1}"
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setIgnoredTextSelectionFlags(0)
                            .setPreferredTextLanguage(lang)
                            .build()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AudioTrackMenu(
    expanded: Boolean,
    tracks: Tracks,
    player: Player,
    onDismiss: () -> Unit,
) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (audioGroups.isEmpty()) {
            DropdownMenuItem(
                text = { Text("Single audio track") },
                onClick = onDismiss,
                enabled = false,
            )
        } else {
            audioGroups.forEachIndexed { i, group ->
                val lang = if (group.mediaTrackGroup.length > 0) {
                    group.mediaTrackGroup.getFormat(0).language
                } else {
                    null
                }
                val label = lang ?: "Audio ${i + 1}"
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setPreferredAudioLanguage(lang)
                            .build()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun AspectRatioMenu(
    expanded: Boolean,
    current: VideoScaleMath.AspectMode,
    onSelected: (VideoScaleMath.AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        VideoScaleMath.AspectMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                onClick = { onSelected(mode) },
            )
        }
    }
}

/**
 * APP-384 #1 — a small, de-emphasized group label inside the ⋯ overflow, used to visually
 * separate the **Playback settings** group from the secondary **File actions** group.
 */
@Composable
private fun MenuSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** ExoPlayer duration in ms, clamped to a non-negative value (UNSET/live → 0). */
private fun Player.positiveDurationMs(): Long {
    val d = duration
    return if (d > 0L) d else 0L
}
