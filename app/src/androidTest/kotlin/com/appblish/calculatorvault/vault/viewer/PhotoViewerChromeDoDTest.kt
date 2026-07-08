package com.appblish.calculatorvault.vault.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * CalcVault Phase B · Wave 1 · W1-E1 DoD (design sign-off [APP-253] §4) — the full-screen
 * viewer's **floating chrome contract**: the top bar exposes back / rotate / info and the
 * bottom bar exposes the three single-purpose actions in the spec §1 terminology lock —
 * **Unhide · Delete · Move** (never "Restore" for the gallery-exit verb). A real photo is
 * hidden into the encrypted vault, the actual [PagerViewerScreen] is composed, and:
 *
 *  - all chrome affordances are present and displayed;
 *  - the **Move** and **Info** actions invoke their W1-E2 callbacks with the current item;
 *  - a **single tap** on the photo toggles the chrome (hides then re-shows the bars),
 *    which is how the viewer gets out of the way of the image.
 *
 * Gesture priority (swipe vs. zoom/pan) and reset are proven in [PagerViewerDoDTest];
 * FLAG_SECURE on the window is proven in
 * [com.appblish.calculatorvault.FlagSecureDoDTest]; in-memory-only decrypt + no cleartext
 * on browsable storage is proven in [PagerViewerViewModel]'s unit suite and the vault
 * pipeline DoD tests.
 */
@RunWith(AndroidJUnit4::class)
class PhotoViewerChromeDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_viewer_chrome"
    private val name = "calcvault_dod_chrome_${System.nanoTime()}.jpg"
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
        DoDTestSupport.deleteImageRows(context, name)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun chromeExposesRotateInfoAndUnhideMoveDeleteAndTapTogglesIt() {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        val stored =
            runBlocking {
                repo.hide(listOf(staged("staged", name, sortKey = 1_000, uri = insert(name, Color.rgb(30, 90, 200)))))
            }
        assertThat(stored).hasSize(1)
        val startId = stored.single().id

        var movedId: String? = null
        var infoId: String? = null
        val viewModel =
            PagerViewerViewModel(startId, VaultCategory.PHOTOS, folderId = null, context = context, repository = repo)
        compose.setContent {
            CalculatorVaultTheme {
                PagerViewerScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onMove = { movedId = it.id },
                    onInfo = { infoId = it.id },
                )
            }
        }

        // The photo decodes and the chrome is shown by default.
        waitForContentDescription(name)
        waitForText("1 / 1")

        // Top bar: back / rotate / info are all present affordances.
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotate").assertIsDisplayed()
        compose.onNodeWithContentDescription("Info").assertIsDisplayed()

        // Bottom bar: the spec §1 terminology lock — Unhide / Delete / Move (no "Restore").
        compose.onNodeWithText("Unhide").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Move").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Restore").fetchSemanticsNodes()).isEmpty()

        // Rotate is a live in-session transform — clicking it must never crash the viewer.
        compose.onNodeWithContentDescription("Rotate").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(name).assertIsDisplayed()

        // Move / Info route to the W1-E2 callbacks with the current item. The clickable is
        // the IconButton, so target the icon by its content description (not the label Text).
        compose.onNodeWithContentDescription("Move").performClick()
        compose.waitForIdle()
        assertThat(movedId).isEqualTo(startId)
        compose.onNodeWithContentDescription("Info").performClick()
        compose.waitForIdle()
        assertThat(infoId).isEqualTo(startId)

        // A single tap on the photo hides the chrome (bars leave the tree)…
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Unhide").fetchSemanticsNodes().isEmpty()
        }
        // …and a second tap brings it back.
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
        waitForText("Unhide")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(description: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
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
        displayName: String,
        sortKey: Long,
        uri: String,
    ): VaultItem =
        VaultItem(
            id = id,
            category = VaultCategory.PHOTOS,
            originalName = displayName,
            dateLabel = "Today",
            sortKey = sortKey,
            sourceUri = uri,
            mimeType = "image/jpeg",
            relativePath = relativePath,
        )
}
