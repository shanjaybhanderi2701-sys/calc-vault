package com.appblish.calculatorvault.vault.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalcVault Phase B · APP-398 (APP-380 round 5b) — the **composed-stack** DoD gate.
 *
 * Round 5a passed [SeekbarDragScrubDoDTest] because that test drove [ThinSeekbar] *in isolation*. On a
 * real device the seekbar lives on a page **inside the viewer's [HorizontalPager]** (see
 * `PagerViewerScreen.ViewerPager`), whose horizontal-scroll detector is an ancestor. The isolated test
 * could never reproduce that ancestor, so it missed the regression the owner hit: pressing and dragging
 * the seekbar **paged the whole layout** instead of moving the thumb — the pager stole the horizontal
 * drag.
 *
 * This test reconstructs that exact composition — a [ThinSeekbar] on the current page of a real,
 * user-scrollable [HorizontalPager] — and locks the fix:
 *  - dragging the seekbar emits **live** scrub fractions (thumb follows the finger), AND
 *  - the pager's `currentPage` **does not change** (no layout shift / page flip), because the seekbar
 *    consumes its own drag on the Initial pass before the pager can claim it.
 *
 * A control case swipes the page *body* (not the seekbar) and asserts the pager **does** page, proving
 * the pager is genuinely scrollable here — so the seekbar's non-paging is a real suppression, not a
 * dead/disabled pager.
 */
@RunWith(AndroidJUnit4::class)
class SeekbarPagerConflictDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val seekbarTag = "conflict_seekbar"
    private val bodyTag = "conflict_body"

    private val emitted = mutableListOf<Float>()
    private var currentPage = -1

    private fun setPagerWithSeekbar(initial: Float) {
        emitted.clear()
        currentPage = -1
        compose.setContent {
            CalculatorVaultTheme {
                Surface(color = Color.Black) {
                    val pagerState = rememberPagerState(initialPage = 0) { 3 }
                    // Expose the settled page to the test on every recomposition.
                    currentPage = pagerState.currentPage
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().testTag("conflict_pager"),
                    ) { page ->
                        if (page == 0) {
                            var fraction by remember { mutableFloatStateOf(initial) }
                            Column(Modifier.fillMaxSize()) {
                                // The seekbar sits in a bottom-bar-like row, exactly like the player.
                                ThinSeekbar(
                                    fraction = fraction,
                                    onScrub = {
                                        fraction = it
                                        emitted += it
                                    },
                                    onScrubFinished = { },
                                    modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(seekbarTag),
                                )
                                // Empty body area used by the control swipe (pages the pager).
                                Box(Modifier.fillMaxWidth().height(400.dp).testTag(bodyTag))
                            }
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Dragging the seekbar scrubs live and must NOT flip the pager page (the round-5a regression). */
    @Test
    fun dragOnSeekbar_scrubsLive_andDoesNotPage() {
        setPagerWithSeekbar(0f)
        assertEquals("precondition: start on page 0", 0, currentPage)

        compose.onNodeWithTag(seekbarTag).performTouchInput {
            down(centerLeft)
            moveTo(center)
            moveTo(centerRight)
            up()
        }
        compose.waitForIdle()

        // Live scrub: several distinct fractions tracked the finger.
        assertTrue("expected live scrub updates, got $emitted", emitted.distinct().size >= 3)
        assertTrue("first emit near start, was ${emitted.firstOrNull()}", (emitted.firstOrNull() ?: 1f) < 0.25f)
        assertTrue("final emit near end, was ${emitted.lastOrNull()}", (emitted.lastOrNull() ?: 0f) > 0.75f)

        // The regression gate: the ancestor pager must not have moved.
        assertEquals("seekbar drag must NOT change the pager page", 0, currentPage)
    }

    /** Control: a swipe on the page *body* (not the seekbar) DOES page — proving the pager is live. */
    @Test
    fun swipeOnBody_pagesTheHorizontalPager() {
        setPagerWithSeekbar(0f)
        assertEquals("precondition: start on page 0", 0, currentPage)

        compose.onNodeWithTag(bodyTag).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue("body swipe should advance the pager, still on $currentPage", currentPage >= 1)
    }
}
