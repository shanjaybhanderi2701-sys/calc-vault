package com.appblish.calculatorvault.vault

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.components.DateGroupedMediaGrid
import com.appblish.calculatorvault.ui.components.GridDragSelectCallbacks
import com.appblish.calculatorvault.ui.components.MediaItem
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.actions.UnhideMessages
import com.appblish.calculatorvault.vault.media.BulkOpProgress
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * W1-E3 Definition-of-Done (spec §7), proven mechanically on the CI matrix — no manual
 * emulator screenshots:
 *
 *  1. **Drag-select correctness** through the real pointer pipeline: a genuine
 *     long-press-drag touch sequence on [DateGroupedMediaGrid] sweeps a contiguous range
 *     and a retreat releases only the swept tiles (hosted grid-only so tile coordinates
 *     stay fixed while the gesture runs).
 *  2. **Select All + bulk delete surviving navigate-away-and-back** on the composed
 *     [CategoryScreen]: the batch runs app-scoped (not viewModelScope), so disposing the
 *     screen mid-op loses nothing — on return the grid is empty and the "N items deleted"
 *     summary snackbar still shows.
 *  3. **Bulk unhide through the real encrypted repository**: blobs stay ciphertext at rest
 *     (never JPEG magic), the batch publishes tracked [BulkOpProgress] (the foreground
 *     service's feed), a multi-megabyte item streams back byte-identical (digest compare —
 *     no full-file heap buffering in the test either), and the per-destination result
 *     summary is honest ("Unhid 2 · saved 1 to DCIM/Restored (original unavailable).").
 *  4. **Bulk permanent delete**: secure blob wipe + encrypted stored-thumb removal + an
 *     accurate destroyed-count even when the selection holds a stale id.
 */
@RunWith(AndroidJUnit4::class)
class MultiSelectBulkOpsDoDTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_w1e3"
    private val bucket = "CalcVaultDoDW1E3"
    private val relativePath = "Pictures/$bucket/"
    private val names = (0 until 3).map { "calcvault_w1e3_${it}_${System.nanoTime()}.jpg" }

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        names.forEach { DoDTestSupport.deleteImageRows(context, it) }
        HideImportViewModel.consumeHideSummary()
        BulkOps.consume()
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        HideImportViewModel.consumeHideSummary()
        BulkOps.consume()
        names.forEach { DoDTestSupport.deleteImageRows(context, it) }
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    // ---------------------------------------------------------------------------------
    // 1 · Drag-select correctness on the real grid + gesture layer (composed UI)
    // ---------------------------------------------------------------------------------

    @Test
    fun longPressDragSweepsARangeAndRetreatReleasesSweptTiles() {
        val (repo, stored) = seedSixPhotos()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        vm.openFolder(CategoryState.RECENT_FOLDER_ID)

        // Host the real grid + real gesture wiring, isolated from the screen chrome so
        // tile coordinates stay put while the gesture runs (the full screen swaps its
        // header for the action bar mid-gesture, which shifts the grid).
        compose.setContent {
            CalculatorVaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                DateGroupedMediaGrid(
                    items = state.folderItems.map { MediaItem(it.id, it.dateLabel, it.sortKey) },
                    selectionMode = state.selectionMode,
                    selectedIds = state.selectedIds,
                    checkIcon = Icons.Filled.Check,
                    onItemClick = { vm.toggle(it.id) },
                    onItemLongPress = { vm.startSelection(it.id) },
                    dragSelect =
                        GridDragSelectCallbacks(
                            onDragStart = vm::beginDragSelect,
                            onDragOver = vm::dragSelectOver,
                            onDragEnd = vm::endDragSelect,
                        ),
                )
            }
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("media-tile-${stored[0].id}").fetchSemanticsNode() }.isSuccess
        }

        // Capture stable tile centers up front (grid-local coordinates).
        val gridOrigin =
            compose
                .onNodeWithTag("media-grid")
                .fetchSemanticsNode()
                .boundsInRoot
                .topLeft
        val centers =
            stored.take(3).map { item ->
                compose
                    .onNodeWithTag("media-tile-${item.id}")
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .center - gridOrigin
            }

        // Long-press p0 (grid detector anchors + enters selection), sweep to p2, retreat
        // to p1, lift. Expected: p0..p1 selected — the retreat released p2 (S17 range
        // semantics, proven through the real pointer pipeline).
        compose.onNodeWithTag("media-grid").performTouchInput {
            down(centers[0])
            advanceEventTime(800) // hold past the long-press timeout without moving
            moveTo(centers[1])
            moveTo(centers[2])
            moveTo(centers[1])
            up()
        }
        compose.waitUntil(5_000) {
            vm.state.value.selectedIds == setOf(stored[0].id, stored[1].id)
        }
        assertThat(vm.state.value.selectionMode).isTrue()
    }

    // ---------------------------------------------------------------------------------
    // 2 · Select All + bulk delete surviving navigate-away-and-back (full screen)
    // ---------------------------------------------------------------------------------

    @Test
    fun selectAllAndBulkDeleteSurviveNavigateAwayAndBack() {
        val (repo, stored) = seedSixPhotos()
        val vm = CategoryViewModel(VaultCategory.PHOTOS, repo)
        vm.openFolder(CategoryState.RECENT_FOLDER_ID)

        var screenVisible by mutableStateOf(true)
        compose.setContent {
            CalculatorVaultTheme {
                if (screenVisible) {
                    CategoryScreen(viewModel = vm, onBack = {}, onOpenItem = {}, onHide = {})
                }
            }
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("media-tile-${stored[0].id}").fetchSemanticsNode() }.isSuccess
        }

        // Long-press enters selection mode (via the grid's drag detector) with 1 selected.
        compose.onNodeWithTag("media-tile-${stored[0].id}").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()

        // Select All grabs the entire folder and the count tracks it.
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("6 selected").assertExists()

        // Bulk permanent delete, then immediately "navigate away" mid-batch.
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        screenVisible = false
        compose.waitForIdle()

        // The batch is app-scoped: it completes with no screen attached.
        compose.waitUntil(10_000) { runBlocking { repo.items(VaultCategory.PHOTOS).first().isEmpty() } }

        // Come back: grid is empty and the pending summary snackbar still surfaces.
        screenVisible = true
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("6 items deleted").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("empty_go_to_hide").assertExists()
    }

    /** Six folder-less photos with descending sort keys → display order p0, p1, … p5. */
    private fun seedSixPhotos(): Pair<InMemoryVaultContentRepository, List<VaultItem>> {
        val repo = InMemoryVaultContentRepository(seed = false)
        val stored =
            runBlocking {
                repo.hide(
                    (0 until 6).map { i ->
                        VaultItem(
                            id = "p$i",
                            category = VaultCategory.PHOTOS,
                            originalName = "p$i.jpg",
                            dateLabel = "Today",
                            sortKey = (100 - i).toLong(),
                        )
                    },
                )
            }
        return repo to stored
    }

    // ---------------------------------------------------------------------------------
    // 3 · Bulk unhide: encrypted at rest, tracked progress, streaming, honest summary
    // ---------------------------------------------------------------------------------

    @Test
    fun bulkUnhideStreamsBackEncryptedItemsWithTrackedProgressAndHonestSummary() =
        runBlocking {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // Plant two small originals with a known home and one LARGE (24 MB) item whose
            // original path is unknown — the documented Downloads/Restored fallback case.
            val small0 = DoDTestSupport.sampleJpegBytes()
            val small1 = DoDTestSupport.sampleJpegBytes()
            val large = ByteArray(24 * 1024 * 1024) { (it % 251).toByte() }
            val stored =
                listOf(
                    hideOne(repo, names[0], small0, keepOriginalPath = true),
                    hideOne(repo, names[1], small1, keepOriginalPath = true),
                    hideOne(repo, names[2], large, keepOriginalPath = false),
                )

            // Encrypted at rest: every blob under .CalcVault/ is ciphertext, never an image.
            val vaultDir = VaultStorage.vaultDir(context)
            val blobs = vaultDir.listFiles { f -> f.isFile && f.name.matches(DoDTestSupport.UUID_REGEX) }.orEmpty()
            assertThat(blobs).hasLength(3)
            blobs.forEach { blob ->
                val head = ByteArray(3)
                blob.inputStream().use { it.read(head) }
                assertThat(head).isNotEqualTo(DoDTestSupport.JPEG_MAGIC)
            }

            // Collect the foreground service's progress feed while the batch runs; wait
            // for the collector to observe the current (idle) state before starting.
            val progress = mutableListOf<BulkOpProgress.Progress?>()
            val collector =
                CoroutineScope(Dispatchers.IO).launch {
                    BulkOpProgress.progress.collect { synchronized(progress) { progress += it } }
                }
            repeat(100) { if (synchronized(progress) { progress.isEmpty() }) Thread.sleep(20) }

            val result = repo.unhideTo(stored.map { it.id }.toSet(), UnhideDestination.Original)
            collector.cancel()

            // Honest per-destination summary: 2 landed home, the pathless one fell back.
            assertThat(result.requested).isEqualTo(2)
            assertThat(result.fellBack).isEqualTo(1)
            assertThat(result.failed).isEqualTo(0)
            val summary = UnhideMessages.summary(result)
            assertThat(summary).contains("Unhid 2")
            assertThat(summary).contains("saved 1 to")

            // The batch ran as a tracked bulk op (label + total the notification renders)
            // and finished clean (null = service tear-down signal).
            val seen = synchronized(progress) { progress.toList() }
            assertThat(seen.filterNotNull().map { it.label }).contains("Unhiding files")
            assertThat(seen.filterNotNull().maxOf { it.total }).isEqualTo(3)
            assertThat(BulkOpProgress.progress.value).isNull()

            // All three are public again; the large one streamed back byte-identical
            // (digest compare — the test never buffers the file whole either).
            assertThat(DoDTestSupport.imageRowCount(context, names[0])).isEqualTo(1)
            assertThat(DoDTestSupport.imageRowCount(context, names[1])).isEqualTo(1)
            assertThat(DoDTestSupport.imageRowCount(context, names[2])).isEqualTo(1)
            assertThat(publicImageDigest(names[2])).isEqualTo(digest(large))
            // The vault let go of everything: no items, no blobs left behind.
            assertThat(repo.allItems().first()).isEmpty()
            assertThat(
                vaultDir.listFiles { f -> f.isFile && f.name.matches(DoDTestSupport.UUID_REGEX) }.orEmpty(),
            ).isEmpty()
        }

    // ---------------------------------------------------------------------------------
    // 4 · Bulk permanent delete: secure wipe + thumb removal + accurate count
    // ---------------------------------------------------------------------------------

    @Test
    fun bulkPermanentDeleteWipesBlobsAndThumbsAndReportsAnAccurateCount() =
        runBlocking {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            val stored =
                listOf(
                    hideOne(repo, names[0], DoDTestSupport.sampleJpegBytes(), keepOriginalPath = true),
                    hideOne(repo, names[1], DoDTestSupport.sampleJpegBytes(), keepOriginalPath = true),
                )
            stored.forEach { repo.saveThumbnail(it.id, DoDTestSupport.sampleJpegBytes()) }
            val thumbFiles =
                stored.map { item ->
                    VaultStorage.thumbFile(context, item.encryptedPath!!.substringAfterLast('/'))
                }
            thumbFiles.forEach { assertThat(it.exists()).isTrue() }

            // A stale id in the selection must not inflate the destroyed-count.
            val deleted = repo.permanentlyDelete(stored.map { it.id }.toSet() + "gone-already")

            assertThat(deleted).isEqualTo(2)
            val vaultDir = VaultStorage.vaultDir(context)
            assertThat(
                vaultDir.listFiles { f -> f.isFile && f.name.matches(DoDTestSupport.UUID_REGEX) }.orEmpty(),
            ).isEmpty()
            thumbFiles.forEach { assertThat(it.exists()).isFalse() }
            assertThat(repo.allItems().first()).isEmpty()
            assertThat(repo.recycleBin().first()).isEmpty()
        }

    /** Plant a public image, hide it through [repo], and drop the public original. */
    private suspend fun hideOne(
        repo: EncryptedVaultContentRepository,
        name: String,
        bytes: ByteArray,
        keepOriginalPath: Boolean,
    ): VaultItem {
        val sourceUri = DoDTestSupport.insertPublicImage(context, name, relativePath, bytes)
        val staged =
            VaultItem(
                id = "staged-$name",
                category = VaultCategory.PHOTOS,
                originalName = name,
                dateLabel = "Today",
                sortKey = System.currentTimeMillis(),
                sourceUri = sourceUri.toString(),
                mimeType = "image/jpeg",
                relativePath = if (keepOriginalPath) relativePath else null,
            )
        val storedItem = repo.hide(listOf(staged)).single()
        context.contentResolver.delete(sourceUri, null, null)
        return storedItem
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** SHA-256 of a public image, streamed in 1 MB chunks (never the whole file in heap). */
    private fun publicImageDigest(displayName: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val resolver = context.contentResolver
        val id =
            resolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assertThat(id).isNotNull()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id!!)
        resolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
