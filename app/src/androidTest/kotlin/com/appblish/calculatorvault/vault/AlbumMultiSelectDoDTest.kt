package com.appblish.calculatorvault.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W2-E DoD proof for the **album multi-select surface + terminology lock** (APP-279 §9/§1,
 * folding in APP-218) at the Compose layer, driven over the in-memory repository exactly
 * like the shipped W1 [MultiSelectBulkOpsDoDTest]:
 *
 * 1. Long-press an album tile enters selection ("1 selected"), Select All sweeps every
 *    real album (never the "Recent" pseudo-folder), and the album-aware Bin delete runs
 *    app-scoped — it completes with the screen gone and its summary still surfaces on
 *    return (navigate-away-and-back).
 * 2. Rename (N=1 only) is a label-editor dialog prefilled with the current name; the
 *    renamed tile is the confirmation.
 * 3. Terminology: FAB menu says "Create album", the create dialog is titled "New album"
 *    with the prefilled + pre-selected "New album" text (OK on the untouched prefill
 *    creates that literal name), and duplicates are rejected inline.
 */
@RunWith(AndroidJUnit4::class)
class AlbumMultiSelectDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        BulkOps.consume()
        HideImportViewModel.consumeHideSummary()
    }

    @After
    fun cleanUp() {
        BulkOps.consume()
        HideImportViewModel.consumeHideSummary()
    }

    private data class Seed(
        val repo: InMemoryVaultContentRepository,
        val cameraId: String,
        val screenshotsId: String,
        val cameraItemIds: Set<String>,
    )

    /** Two real albums (2 + 1 photos) on a seedless repo — the whole root grid. */
    private fun seedTwoAlbums(): Seed =
        runBlocking {
            val repo = InMemoryVaultContentRepository(seed = false)
            val camera = repo.createFolder(VaultCategory.PHOTOS, "Camera")
            val shots = repo.createFolder(VaultCategory.PHOTOS, "Screenshots")
            val items =
                repo.hide(
                    listOf(
                        staged("a", 3L),
                        staged("b", 2L),
                        staged("c", 1L),
                    ),
                )
            repo.moveToFolder(setOf(items[0].id, items[1].id), camera.id)
            repo.moveToFolder(setOf(items[2].id), shots.id)
            Seed(repo, camera.id, shots.id, setOf(items[0].id, items[1].id))
        }

    private fun staged(
        id: String,
        sortKey: Long,
    ) = VaultItem(
        id = id,
        category = VaultCategory.PHOTOS,
        originalName = "$id.jpg",
        dateLabel = "Today",
        sortKey = sortKey,
    )

    private fun awaitTile(tag: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess
        }
    }

    // ---------------------------------------------------------------------------------
    // 1 · Selection + Select All + Bin delete surviving navigate-away-and-back
    // ---------------------------------------------------------------------------------

    @Test
    fun albumSelectAllAndBinDeleteSurviveNavigateAwayAndBack() {
        val (repo, cameraId, _, cameraItemIds) = seedTwoAlbums()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)

        var screenVisible by mutableStateOf(true)
        compose.setContent {
            CalculatorVaultTheme {
                if (screenVisible) {
                    CategoryScreen(viewModel = vm, onBack = {}, onOpenItem = {}, onHide = {})
                }
            }
        }
        awaitTile("album-tile-$cameraId")

        // Long-press enters album selection with the pressed tile selected.
        compose.onNodeWithTag("album-tile-$cameraId").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        assertThat(vm.state.value.albumSelectionMode).isTrue()

        // Select All sweeps both real albums; the count tracks it.
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("2 selected").assertExists()

        // Delete → the album-aware 2-step dialog, Bin as the safe default. The copy spells
        // out album + contents ("2 albums and their 3 photos…").
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete 2 albums?").assertExists()
        compose
            .onNodeWithText("2 albums and their 3 photos move to the Recycle Bin, recoverable for 30 days.")
            .assertExists()
        compose.onNodeWithText("Move to Recycle Bin").performClick()

        // Navigate away mid-batch: the op is app-scoped and completes screenless.
        screenVisible = false
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            runBlocking {
                repo.folders(VaultCategory.PHOTOS).first().isEmpty() &&
                    repo.recycleBin().first().size == 3
            }
        }

        // Come back: the pending album-aware summary still surfaces (never silent).
        screenVisible = true
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("2 albums · 3 photos moved to Recycle Bin").fetchSemanticsNodes().isNotEmpty()
        }
        // Bin restore brings an album back whole (F-3) — repository-level check.
        runBlocking {
            repo.restore(cameraItemIds)
            val back = repo.folders(VaultCategory.PHOTOS).first().single()
            assertThat(back.id).isEqualTo(cameraId)
            assertThat(back.name).isEqualTo("Camera")
        }
    }

    // ---------------------------------------------------------------------------------
    // 2 · Rename (N=1): label-editor dialog, prefilled, tile re-renders
    // ---------------------------------------------------------------------------------

    @Test
    fun renameAlbumDialogRenamesTheTile() {
        val (repo, cameraId, _, _) = seedTwoAlbums()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)

        compose.setContent {
            CalculatorVaultTheme {
                CategoryScreen(viewModel = vm, onBack = {}, onOpenItem = {}, onHide = {})
            }
        }
        awaitTile("album-tile-$cameraId")

        compose.onNodeWithTag("album-tile-$cameraId").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        // W3-E (W3-D §4): the N=1 identity actions moved into the selection bar's ⋯
        // overflow — Rename · Pin album · Set as cover.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename album").assertExists()

        // Field arrives prefilled with the current name (the editable node — the grid tile
        // behind the dialog also reads "Camera"); type-to-replace, then OK.
        compose.onNode(hasSetTextAction()).performTextReplacement("Sunsets")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Sunsets").fetchSemanticsNodes().isNotEmpty()
        }
        // Index-label-only: same album id, same member items, new label.
        runBlocking {
            val folder = repo.folders(VaultCategory.PHOTOS).first().first { it.id == cameraId }
            assertThat(folder.name).isEqualTo("Sunsets")
            assertThat(
                repo.allItems().first().count { it.folderId == cameraId },
            ).isEqualTo(2)
        }
    }

    // ---------------------------------------------------------------------------------
    // 3 · Terminology lock: "Create album" FAB row, "New album" prefilled dialog
    // ---------------------------------------------------------------------------------

    @Test
    fun createAlbumDialogFollowsTheTerminologyLock() {
        val (repo, cameraId, _, _) = seedTwoAlbums()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)

        compose.setContent {
            CalculatorVaultTheme {
                CategoryScreen(viewModel = vm, onBack = {}, onOpenItem = {}, onHide = {})
            }
        }
        awaitTile("album-tile-$cameraId")

        // FAB menu says "Create album" — never "Create Folder" (APP-218 G2b/c).
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("Create album").assertExists()
        compose.onNodeWithText("Create album").performClick()

        // Dialog titled "New album" with the same prefilled field text (two nodes read
        // "New album": title + field); OK on the untouched prefill creates an album
        // literally named "New album" (xlock behaviour).
        assertThat(compose.onAllNodesWithText("New album").fetchSemanticsNodes().size).isAtLeast(2)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            runBlocking { repo.folders(VaultCategory.PHOTOS).first().any { it.name == "New album" } }
        }

        // Re-open: the untouched prefill now collides — inline duplicate error, OK dead.
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("Create album").performClick()
        compose.onNodeWithText("An album with this name already exists").assertExists()
        compose.onNodeWithText("OK").assertIsNotEnabled()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()
        runBlocking {
            assertThat(
                repo.folders(VaultCategory.PHOTOS).first().count { it.name == "New album" },
            ).isEqualTo(1)
        }
    }
}
