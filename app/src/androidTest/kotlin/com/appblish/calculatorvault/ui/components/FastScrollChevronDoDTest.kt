package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-320 — the redesigned fast-scroll handle is a **pill bearing an up/down double-arrow
 * (chevron)**, not the old thin line. This regressed twice on "marked done without real
 * verification", so the rendered glyph is proven on-device here: on a long grid, once the
 * handle is visible (scroll activity), the pill exists AND the chevron glyph
 * ([testTag] `fast-scroll-chevron`) is rendered inside it. The instrumented matrix (API
 * 30/35) runs this in CI as the deterministic counterpart to the manual screen-capture.
 */
@RunWith(AndroidJUnit4::class)
class FastScrollChevronDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longGridHandleShowsPillWithChevron() {
        compose.setContent {
            CalculatorVaultTheme {
                Surface {
                    val gridState = rememberLazyGridState()
                    val labels = remember { (0 until 120).map { "Item $it" } }
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize().testTag("grid"),
                        ) {
                            items(labels) { label ->
                                Text(label, modifier = Modifier.padding(24.dp).aspectRatio(1f))
                            }
                        }
                        FastScrollbar(
                            state = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            labelForIndex = { "#$it" },
                        )
                    }
                }
            }
        }

        // Scroll activity reveals the auto-hiding handle; poll within the visible window.
        compose.onNodeWithTag("grid").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("fast-scroll-handle").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("fast-scroll-handle").assertExists()
        // The chevron glyph is the APP-320 delta — a plain pill would fail here.
        compose.onNodeWithTag("fast-scroll-chevron", useUnmergedTree = true).assertExists()
    }
}
