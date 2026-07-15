package com.appblish.playerkit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Thin seekbar dimensions/colors (APP-388 #1). A 2dp line with a 12dp round thumb replaces the
// default Material Slider (≈4dp track / ≈20dp pill). APP-395: the Material3 Slider slot API
// positioned the thumb and the custom track with two independent vertical-offset computations, which
// drifted off each other on-device. Replaced by [ThinSeekbar] — both the track and the thumb are
// laid out in one Box anchored to the SAME vertical centre line, so they can never diverge.
private val SEEK_TRACK_HEIGHT = 2.dp
private val SEEK_THUMB_DIAMETER = 12.dp

// The invisible grab/touch area around the thin visuals — thumb + track are centred within it.
// APP-418: this was 24dp. On a real device a finger aiming at the thin 2dp line routinely landed a
// few px above/below the 24dp band, so the press fell through to the surface body's swipe-to-seek
// layer instead of grabbing the seekbar. Bumped to the 48dp Material minimum touch target so a
// natural press reliably lands inside the seekbar bounds and the seekbar owns the whole gesture. The
// visible track (2dp) + thumb (12dp) are unchanged and stay centred.
private val SEEK_TOUCH_HEIGHT = 48.dp
private val SeekInactiveColor = Color(0x66FFFFFF)

// Test tags so an instrumented layout assertion can prove the thumb's vertical centre matches the
// track's vertical centre.
internal const val SEEK_THUMB_TAG = "seek_thumb"
internal const val SEEK_TRACK_TAG = "seek_track"

/**
 * The shared player's transport controls (APP-408; MX seekbar redesign forward-ported by APP-438):
 * a thin scrub bar over timestamps, with an optional live scrub-preview thumbnail. The bar consumes
 * its own drags on the **Initial** pointer pass so scrubbing can never leak into the host pager
 * (design §3 gesture ownership; the APP-398 pager-gating fix). App-agnostic — the accent [color] is
 * a parameter so both apps brand it without the kit depending on either theme. Timestamps use
 * [VideoGestureMath.formatTime].
 *
 * When a [storyboard] strip is supplied and a scrub is in progress ([scrubFraction] != null), the
 * frame nearest the scrub position floats above the bar (YouTube/MX "hover" style). The strip is
 * acquired + decoded by the kit's [VideoStoryboardCache]; this composable only crops.
 */
@Composable
fun VideoPlayerControls(
    positionMs: Long,
    durationMs: Long,
    scrubFraction: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    storyboard: VideoStoryboard.Strip? = null,
) {
    Column(modifier = modifier.testTag("player_controls")) {
        val playedFraction =
            if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val fraction = scrubFraction ?: playedFraction

        // Scrub preview floats above the bar, only while dragging and only if a strip is available.
        if (storyboard != null && scrubFraction != null) {
            ScrubPreview(strip = storyboard, fraction = scrubFraction)
        }

        ThinSeekbar(
            fraction = fraction,
            activeColor = color,
            onScrub = onScrub,
            onScrubFinished = onScrubFinished,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            val scrubMs = scrubFraction?.let { (it * durationMs).toLong() }
            Text(
                text = VideoGestureMath.formatTime(scrubMs ?: positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = VideoGestureMath.formatTime(durationMs),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The thin video seekbar (APP-395 round 4). Replaces the Material3 `Slider` whose slot API computed
 * the thumb offset and the custom-track offset independently, letting the round thumb drift off the
 * 2dp track vertically on-device.
 *
 * Centring guarantee: the track and the thumb live in ONE [Box] and are both anchored to the *same*
 * vertical centre line — no second, independent vertical-offset computation that could diverge — so
 * the thumb's centre sits exactly on the track's centre at every [fraction] and while dragging.
 *
 * @param fraction current progress in `0f..1f`.
 * @param activeColor the accent colour for the filled track + thumb.
 * @param onScrub called with the touched fraction on tap and continuously during a drag.
 * @param onScrubFinished called once the gesture ends (parent commits the latched scrub position).
 */
@Composable
internal fun ThinSeekbar(
    fraction: Float,
    activeColor: Color,
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
                    .background(activeColor),
            )
        }

        // ---- Thumb: same box, same centre line, only translated horizontally by the fraction ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(x = (travelPx * clamped).roundToInt(), y = 0) }
                .size(SEEK_THUMB_DIAMETER)
                .clip(CircleShape)
                .background(activeColor)
                .testTag(SEEK_THUMB_TAG),
        )

        // ---- Gesture layer covering the whole touch area (tap-to-seek + press-drag-to-scrub) ----
        // APP-398 (round 5b): one deterministic gesture loop handles BOTH tap and drag, and it runs on
        // the **Initial** pointer pass so it pre-empts every ancestor gesture. The video page is a child
        // of the viewer's HorizontalPager, whose horizontal-scroll detector is an ancestor; a Main-pass
        // consume happens too late (the pager claims the drag first and pages the layout). Consuming on
        // the Initial pass (root→leaf) marks every event handled before the pager's and the body's
        // Main-pass detectors ever see it, so a drag that begins inside the seekbar stays the seekbar's.
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
 * The live scrub-preview thumbnail (APP-419 P1). While the seekbar is dragged, the frame nearest
 * [fraction] of the timeline floats above the bar. The frame is a pure in-memory sub-rect crop of the
 * already-decoded [VideoStoryboard.Strip] — no re-acquisition per drag tick — and is only re-cropped
 * when the drag crosses into a new storyboard frame (the [remember] key is the frame index). The
 * preview tracks the finger within the bar's width, clamped so it never spills past either edge.
 */
@Composable
private fun ScrubPreview(
    strip: VideoStoryboard.Strip,
    fraction: Float,
) {
    val frameIndex = VideoStoryboard.frameIndexFor(fraction, strip.frameCount)
    val frame = remember(strip, frameIndex) { strip.frameAt(fraction) }
    val aspect = strip.frameWidth.toFloat() / strip.frameHeight.coerceAtLeast(1)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val previewWidth = 104.dp
        val offsetX = ((maxWidth - previewWidth) * fraction.coerceIn(0f, 1f))
            .coerceIn(0.dp, (maxWidth - previewWidth).coerceAtLeast(0.dp))
        Image(
            bitmap = frame,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset(x = offsetX)
                .width(previewWidth)
                .aspectRatio(aspect.coerceIn(0.2f, 5f))
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        )
    }
}
