package com.appblish.calculatorvault.vault.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
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
 * **Priority (so nothing misfires):**
 *  - `detectTapGestures` emits `onTap` only after the double-tap window, so a double-tap is
 *    never mis-read as two toggles.
 *  - A drag latches one axis past touch-slop ([VideoGestureMath.dominantAxis]) and holds it
 *    for the whole gesture, so a horizontal scrub can't leak into brightness/volume.
 *  - Only single-pointer drags start a gesture; a second pointer (Wave-3 pinch) is left free.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerSurface(player: Player) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }

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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceSize = it },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setBackgroundColor(AndroidColor.BLACK)
                    // This overlay owns every touch; the controller only shows on our tap.
                    controllerAutoShow = false
                    controllerHideOnTouch = false
                }
            },
            update = { view ->
                view.player = player
                if (controlsVisible) view.showController() else view.hideController()
            },
        )

        // Layer 1 — single tap / double-tap. onTap fires only after the double-tap window.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(surfaceSize) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { pos ->
                                val zone = VideoGestureMath.zoneFor(pos.x, surfaceSize.width.toFloat())
                                seekTapCount = if (seekLabel != null && zone == seekZone) seekTapCount + 1 else 1
                                seekZone = zone
                                val total = player.durationOrZero()
                                val step = VideoGestureMath.seekDeltaMs(zone, 1)
                                player.seekTo(VideoGestureMath.seekTo(player.currentPosition, step, total))
                                seekLabel = VideoGestureMath.seekLabel(
                                    VideoGestureMath.seekDeltaMs(zone, seekTapCount),
                                )
                                seekNonce++
                            },
                        )
                    },
        )

        // Layer 2 — single-pointer drags: scrub (H) / brightness (V-right) / volume (V-left).
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(surfaceSize) {
                        var axis: VideoGestureMath.Axis? = null
                        var zone = VideoGestureMath.Zone.RIGHT
                        var accumDx = 0f
                        var accumDy = 0f
                        var scrubStartMs = 0L
                        detectDragGestures(
                            onDragStart = { start ->
                                axis = null
                                accumDx = 0f
                                accumDy = 0f
                                zone = VideoGestureMath.zoneFor(start.x, surfaceSize.width.toFloat())
                                scrubStartMs = player.currentPosition
                                scrubTotalMs = player.durationOrZero()
                            },
                            onDragEnd = {
                                if (axis == VideoGestureMath.Axis.HORIZONTAL && scrubVisible) {
                                    player.seekTo(scrubTargetMs)
                                }
                                scrubVisible = false
                            },
                            onDragCancel = { scrubVisible = false },
                        ) { change, dragAmount ->
                            change.consume()
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
                                    if (zone == VideoGestureMath.Zone.RIGHT) {
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
                    },
        )

        // ---- Indicators ----
        seekLabel?.let { label ->
            IndicatorPill(
                text = label,
                alignment = if (seekZone == VideoGestureMath.Zone.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
            )
        }
        if (scrubVisible) {
            IndicatorPill(
                text = "${VideoGestureMath.formatTime(scrubTargetMs)} / ${VideoGestureMath.formatTime(scrubTotalMs)}",
                alignment = Alignment.Center,
            )
        }
        if (brightnessVisible) {
            VerticalLevelIndicator(
                caption = "Brightness",
                fraction = brightnessFraction,
                alignment = Alignment.CenterEnd,
            )
        }
        if (volumeVisible) {
            VerticalLevelIndicator(
                caption = "Volume",
                fraction = volumeFraction,
                alignment = Alignment.CenterStart,
            )
        }
    }
}

/** A dark rounded pill for the seek / scrub-time indicators. */
@Composable
private fun BoxScope.IndicatorPill(
    text: String,
    alignment: Alignment,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .align(alignment)
                .padding(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = text, color = Color.White)
    }
}

/** A slim vertical fill (0..1) with a caption, for brightness / volume. */
@Composable
private fun BoxScope.VerticalLevelIndicator(
    caption: String,
    fraction: Float,
    alignment: Alignment,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .align(alignment)
                .padding(28.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xB3000000))
                .padding(12.dp),
    ) {
        Text(text = caption, color = Color.White)
        Box(
            modifier =
                Modifier
                    .width(6.dp)
                    .fillMaxHeight(0.4f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x40FFFFFF)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White),
            )
        }
        Text(text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%", color = Color.White)
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
