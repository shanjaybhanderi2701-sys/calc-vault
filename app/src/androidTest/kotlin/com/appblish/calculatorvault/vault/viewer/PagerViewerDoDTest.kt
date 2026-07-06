package com.appblish.calculatorvault.vault.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.DoDTestSupport
import com.appblish.calculatorvault.vault.EncryptedVaultContentRepository
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Board-added Phase-1 check #2 (APP-237/APP-241): **items open in the full-screen gallery
 * viewer — swipe between items and pinch-to-zoom on photos.** Two real photos are hidden
 * into the encrypted vault, then the actual [PagerViewerScreen] is composed and driven by
 * real touch input: the page indicator proves the swipe lands on the next item, a pinch
 * proves zoom engages (a zoomed page must NOT hand swipes to the pager — the P0-2
 * contract), and a double-tap proves the zoom resets and swiping resumes. Video playback
 * via Media3 is proven separately in [VideoPlaybackDoDTest].
 */
@RunWith(AndroidJUnit4::class)
class PagerViewerDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_pager"
    private val nameA = "calcvault_dod_pager_a_${System.nanoTime()}.jpg"
    private val nameB = "calcvault_dod_pager_b_${System.nanoTime()}.jpg"
    private val relativePath = "DCIM/CalcVaultDoD/"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteImageRows(context, nameA)
        DoDTestSupport.deleteImageRows(context, nameB)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun swipeAdvancesPagesAndPinchZoomEngagesAndResets() {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        val stored =
            runBlocking {
                repo.hide(
                    listOf(
                        // Higher sortKey = newest = page 1.
                        staged("staged-a", nameA, sortKey = 2_000, uri = insert(nameA, Color.rgb(20, 180, 90))),
                        staged("staged-b", nameB, sortKey = 1_000, uri = insert(nameB, Color.rgb(200, 40, 40))),
                    ),
                )
            }
        assertThat(stored).hasSize(2)
        val startId = stored.single { it.originalName == nameA }.id

        val viewModel =
            PagerViewerViewModel(startId, VaultCategory.PHOTOS, folderId = null, context = context, repository = repo)
        compose.setContent {
            CalculatorVaultTheme {
                PagerViewerScreen(viewModel = viewModel, onBack = {})
            }
        }

        // Page 1 of 2 (the tapped item) is shown with its decoded photo.
        waitForText("1 of 2")
        waitForText(nameA)

        // Swipe left → the pager settles on the second item.
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText("2 of 2")
        waitForText(nameB)

        // Pinch-to-zoom on the photo. While zoomed the pager must ignore swipes
        // (userScrollEnabled=false is the zoom contract), so the page may not change.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(nameB).fetchSemanticsNodes().isNotEmpty()
        }
        val image = compose.onNodeWithContentDescription(nameB)
        image.performTouchInput {
            pinch(
                start0 = center - percentOffset(0.1f, 0f),
                end0 = center - percentOffset(0.4f, 0f),
                start1 = center + percentOffset(0.1f, 0f),
                end1 = center + percentOffset(0.4f, 0f),
            )
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        waitForText("2 of 2") // still page 2: the zoomed page consumed the drag as a pan

        // Double-tap resets zoom → swiping works again and returns to page 1.
        image.performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeRight() }
        waitForText("1 of 2")
        waitForText(nameA)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insert(
        displayName: String,
        color: Int,
    ) = DoDTestSupport
        .insertPublicImage(context, displayName, relativePath, solidJpeg(color))
        .toString()

    private fun solidJpeg(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun staged(
        id: String,
        name: String,
        sortKey: Long,
        uri: String,
    ): VaultItem =
        VaultItem(
            id = id,
            category = VaultCategory.PHOTOS,
            originalName = name,
            dateLabel = "Today",
            sortKey = sortKey,
            sourceUri = uri,
            mimeType = "image/jpeg",
            relativePath = relativePath,
        )
}
