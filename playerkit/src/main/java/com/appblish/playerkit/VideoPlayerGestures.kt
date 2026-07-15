package com.appblish.playerkit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged

/**
 * The shared video surface's gesture dispatcher (APP-408) — a thin Compose adapter over the tested
 * [VideoGestureMath] / [VideoZoomMath] cores (APP-402). It owns nothing that decides *what* a gesture
 * means; it only feeds raw pointer deltas to the math and applies the result to [state].
 *
 * Arbitration (design §3 "gestures must not fight"):
 *  - **Tap** → toggle the controls chrome via [onToggleChrome].
 *  - **Double-tap** → seek: the tapped half of the surface resolves to a [VideoGestureMath.Zone]
 *    (LEFT rewinds, RIGHT advances) handed to [onDoubleTapSeek].
 *  - **Pinch / zoomed drag** → routed to [VideoZoomMath] via [state]; consumed so the host pager
 *    freezes. A single-finger drag at 1× is LEFT UNCONSUMED so the pager can swipe between media.
 *
 * Runs *before* the pager's drag detector (child pointer-input), so what is consumed here is what
 * decides ownership.
 */
fun Modifier.videoPlayerGestures(
    state: VideoZoomState,
    onToggleChrome: () -> Unit,
    onDoubleTapSeek: (VideoGestureMath.Zone) -> Unit,
): Modifier =
    onSizeChanged {
        state.containerWidth = it.width.toFloat()
        state.containerHeight = it.height.toFloat()
    }
        .pointerInput(state) {
            detectTapGestures(
                onTap = { onToggleChrome() },
                onDoubleTap = { tap ->
                    onDoubleTapSeek(VideoGestureMath.zoneFor(tap.x, state.containerWidth))
                },
            )
        }
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val pressedCount = event.changes.count { it.pressed }
                    if (state.shouldConsume(pressedCount)) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            state.applyPinch(zoom, pan.x, pan.y)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
