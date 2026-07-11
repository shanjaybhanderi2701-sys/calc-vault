package com.appblish.calculatorvault.vault.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.appblish.calculatorvault.ui.theme.VaultActionIcons
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import android.graphics.Color as AndroidColor

/**
 * CalcVault Phase B · Wave 2 · APP-349 — the gesture surface that layers over the Wave-1
 * ExoPlayer [PlayerView] (spec §3). All the *decisions* live in [VideoGestureMath]; this
 * composable only wires touch events to the player, the window, and the audio stream, and
 * renders the transient indicators.
 *
 * **Gesture ownership.** The built-in controller (seekbar + play/pause) is kept but put under
 * manual control (`controllerAutoShow = false`, `controllerHideOnTouch = false`) so a single
 * tap here — not PlayerView's own touch handling — toggles it. That gives this overlay full,
 * unambiguous ownership of every touch, which is what makes the zone/priority rules
 * deterministic (the design gate).
 *
 * **Single gesture owner (APP-359).** Tap/double-tap AND drag are resolved inside ONE
 * `pointerInput` (`awaitEachGesture`), not two stacked detectors. Stacking a `detectDragGestures`
 * layer on top of a `detectTapGestures` layer let the drag detector starve the double-tap
 * detector on-device (double-tap seek never fired). One owner removes that contention:
 *  - Touch-slop decides the branch: a finger that lifts before slop is a tap (single vs.
 *    double resolved over the double-tap window, so a double-tap is never read as two toggles);
 *    a finger that crosses slop is a drag.
 *  - A drag latches one axis past touch-slop ([VideoGestureMath.dominantAxis]) and holds it
 *    for the whole gesture, so a horizontal scrub can't leak into brightness/volume.
 *  - Only single-pointer drags start a gesture; a second pointer (Wave-3 pinch) is left free.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerSurface(
    player: Player,
    onToggleControls: () -> Unit,
    locked: Boolean,
    scale: Float,
    panX: Float,
    panY: Float,
    onPinch: (newScale: Float, newPanX: Float, newPanY: Float) -> Unit,
    resizeMode: Int,
    rotationDegrees: Int,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    // Transient indicators (spec §3: every gesture shows on-screen feedback).
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var brightnessFraction by remember { mutableFloatStateOf(currentWindowBrightness(activity)) }
    var brightnessVisible by remember { mutableStateOf(false) }
    var volumeFraction by remember {
        mutableFloatStateOf(currentVolumeFraction(audioManager, maxVolume))
    }
    var volumeVisible by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    var scrubTotalMs by remember { mutableLongStateOf(0L) }
    var scrubVisible by remember { mutableStateOf(false) }

    // Auto-hide tickets: bump a nonce to (re)start the fade timer for each indicator.
    var seekNonce by remember { mutableIntStateOf(0) }
    var brightnessNonce by remember { mutableIntStateOf(0) }
    var volumeNonce by remember { mutableIntStateOf(0) }

    // Double-tap ratchet: consecutive taps on the same side accumulate (−10s, −20s, …).
    var seekZone by remember { mutableStateOf(VideoGestureMath.Zone.RIGHT) }
    var seekTapCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(seekNonce) {
        if (seekNonce == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(650)
        seekLabel = null
        seekTapCount = 0
    }
    LaunchedEffect(brightnessNonce) {
        if (brightnessNonce == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(650)
        brightnessVisible = false
    }
    LaunchedEffect(volumeNonce) {
        if (volumeNonce == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(650)
        volumeVisible = false
    }

    // Restore the system-default brightness when playback leaves composition.
    DisposableEffect(activity) {
        onDispose { activity?.let { restoreSystemBrightness(it) } }
    }

    // Pinch-zoom transform state (Wave 3, spec §5): only multi-touch (≥2 pointer) gestures
    // trigger a zoom change in rememberTransformableState, so this never fights the Wave-2
    // single-pointer handler below. When locked the transformable is disabled (canTransform).
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val (ns, nx, ny) = VideoZoomMath.applyPinch(
            scale,
            panX,
            panY,
            zoomChange,
            panChange.x,
            panChange.y,
            surfaceSize.width.toFloat(),
            surfaceSize.height.toFloat(),
        )
        onPinch(ns, nx, ny)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceSize = it },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                    rotationZ = rotationDegrees.toFloat()
                }.transformable(state = transformableState, enabled = !locked),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    this.resizeMode = resizeMode
                    setBackgroundColor(AndroidColor.BLACK)
                    // APP-384 #2 — the built-in PlayerView controller is fully OFF. It used to
                    // render ExoPlayer's own center prev/rewind/pause cluster + timeline, which
                    // overlapped (and duplicated) this app's overlay. Our [VideoPlayerControlsOverlay]
                    // now owns the *entire* control set (single center play/pause, MX-style bottom
                    // seekbar + control row), so nothing here draws chrome.
                    useController = false
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
            },
        )

        // One gesture owner — tap / double-tap AND drag share a single pointerInput so the
        // detectors never contend (a stacked drag layer used to starve the double-tap detector
        // on-device; spec §3 item 2 — APP-359). Touch-slop routes the gesture: lift-before-slop
        // is a tap, crossing slop is a drag. Entire block is skipped while locked.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(surfaceSize, locked) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // Per-gesture drag bookkeeping (reset on every fresh touch).
                            var axis: VideoGestureMath.Axis? = null
                            var accumDx = 0f
                            var accumDy = 0f
                            val zone = VideoGestureMath.zoneFor(down.position.x, surfaceSize.width.toFloat())
                            val scrubStartMs = player.currentPosition
                            scrubTotalMs = player.durationOrZero()

                            // Applies one drag delta: latch the axis past slop, then scrub (H) or
                            // adjust brightness / volume (V). Mirrors the Wave-1 drag semantics.
                            fun applyDrag(dragAmount: Offset) {
                                accumDx += dragAmount.x
                                accumDy += dragAmount.y
                                if (axis == null && hypot(accumDx, accumDy) > TOUCH_SLOP_PX) {
                                    axis = VideoGestureMath.dominantAxis(accumDx, accumDy)
                                }
                                when (axis) {
                                    VideoGestureMath.Axis.HORIZONTAL -> {
                                        scrubTargetMs =
                                            VideoGestureMath.scrubTargetMs(
                                                scrubStartMs,
                                                accumDx,
                                                surfaceSize.width.toFloat(),
                                                scrubTotalMs,
                                            )
                                        scrubVisible = true
                                    }
                                    VideoGestureMath.Axis.VERTICAL -> {
                                        val h = surfaceSize.height.toFloat()
                                        // APP-384 #4 — MX-Player convention: LEFT edge = brightness,
                                        // RIGHT edge = volume (previously inverted).
                                        if (zone == VideoGestureMath.Zone.LEFT) {
                                            brightnessFraction =
                                                VideoGestureMath.adjustBrightness(brightnessFraction, dragAmount.y, h)
                                            activity?.let { applyWindowBrightness(it, brightnessFraction) }
                                            brightnessVisible = true
                                            brightnessNonce++
                                        } else {
                                            volumeFraction =
                                                VideoGestureMath.adjustVolumeFraction(volumeFraction, dragAmount.y, h)
                                            val idx = VideoGestureMath.volumeIndex(volumeFraction, maxVolume)
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
                                            volumeVisible = true
                                            volumeNonce++
                                        }
                                    }
                                    null -> Unit
                                }
                            }

                            // Wait for the finger to cross touch-slop (→ drag) or lift (→ tap).
                            var overSlop = Offset.Zero
                            val slopChange =
                                awaitTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume()
                                    overSlop = over
                                }

                            if (slopChange != null) {
                                // ---- DRAG: scrub (H) / brightness (V-right) / volume (V-left). ----
                                applyDrag(overSlop)
                                val completed =
                                    drag(slopChange.id) { change ->
                                        applyDrag(change.positionChange())
                                        change.consume()
                                    }
                                if (completed && axis == VideoGestureMath.Axis.HORIZONTAL && scrubVisible) {
                                    player.seekTo(scrubTargetMs)
                                }
                                scrubVisible = false
                            } else {
                                // ---- TAP: single toggles controls, double-tap seeks (spec §3). ----
                                // The finger lifted before slop; wait one double-tap window for a
                                // second touch. A second down → double-tap seek; none → single tap.
                                val secondDown =
                                    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                        awaitFirstDown(requireUnconsumed = false)
                                    }
                                if (secondDown == null) {
                                    onToggleControls()
                                } else {
                                    val tapZone =
                                        VideoGestureMath.zoneFor(secondDown.position.x, surfaceSize.width.toFloat())
                                    seekTapCount =
                                        if (seekLabel != null && tapZone == seekZone) seekTapCount + 1 else 1
                                    seekZone = tapZone
                                    val total = player.durationOrZero()
                                    val step = VideoGestureMath.seekDeltaMs(tapZone, 1)
                                    player.seekTo(VideoGestureMath.seekTo(player.currentPosition, step, total))
                                    seekLabel =
                                        VideoGestureMath.seekLabel(VideoGestureMath.seekDeltaMs(tapZone, seekTapCount))
                                    seekNonce++
                                    // Consume the second tap so it can't also open a drag gesture.
                                    secondDown.consume()
                                }
                            }
                        }
                    },
        )

        // ---- Indicators (APP-384 #4 — large, clean, MX-Player-style overlays) ----
        // Double-tap seek delta ("+10s") stays on the tapped edge.
        seekLabel?.let { label ->
            SeekIndicator(
                text = label,
                alignment = if (seekZone == VideoGestureMath.Zone.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
            )
        }
        // Horizontal-swipe scrub → a big centered time/total card.
        if (scrubVisible) {
            SeekIndicator(
                text = "${VideoGestureMath.formatTime(scrubTargetMs)} / ${VideoGestureMath.formatTime(scrubTotalMs)}",
                alignment = Alignment.Center,
            )
        }
        // Brightness → LEFT edge; Volume → RIGHT edge (MX-Player convention).
        if (brightnessVisible) {
            LevelIndicator(
                icon = VaultActionIcons.Brightness,
                caption = "Brightness",
                fraction = brightnessFraction,
                alignment = Alignment.CenterStart,
            )
        }
        if (volumeVisible) {
            LevelIndicator(
                icon = if (volumeFraction <= 0f) VaultActionIcons.VolumeOff else VaultActionIcons.VolumeOn,
                caption = "Volume",
                fraction = volumeFraction,
                alignment = Alignment.CenterEnd,
            )
        }
    }
}

/** A large, dark centered card for the seek / scrub-time indicators (MX-Player style). */
@Composable
private fun BoxScope.SeekIndicator(
    text: String,
    alignment: Alignment,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .align(alignment)
                .padding(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/**
 * A prominent brightness / volume overlay: an icon over a tall vertical fill (0..1) and a big
 * percentage, in a dark rounded card pinned to the swipe's edge (APP-384 #4, MX-Player style).
 */
@Composable
private fun BoxScope.LevelIndicator(
    icon: ImageVector,
    caption: String,
    fraction: Float,
    alignment: Alignment,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .align(alignment)
                .padding(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Icon(imageVector = icon, contentDescription = caption, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(text = caption, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier =
                Modifier
                    .width(8.dp)
                    .fillMaxHeight(0.5f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x40FFFFFF)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
            )
        }
        Text(
            text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private const val TOUCH_SLOP_PX = 24f

private fun Player.durationOrZero(): Long {
    val d = duration
    return if (d > 0L) d else 0L
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun currentWindowBrightness(activity: Activity?): Float {
    val b = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (b in 0f..1f) b else 0.5f
}

private fun applyWindowBrightness(
    activity: Activity,
    fraction: Float,
) {
    val lp = activity.window.attributes
    lp.screenBrightness = fraction.coerceIn(VideoGestureMath.MIN_BRIGHTNESS, VideoGestureMath.MAX_BRIGHTNESS)
    activity.window.attributes = lp
}

private fun restoreSystemBrightness(activity: Activity) {
    val lp = activity.window.attributes
    lp.screenBrightness = -1f // BRIGHTNESS_OVERRIDE_NONE
    activity.window.attributes = lp
}

private fun currentVolumeFraction(
    audioManager: AudioManager?,
    maxVolume: Int,
): Float {
    if (audioManager == null || maxVolume <= 0) return 0f
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
}
