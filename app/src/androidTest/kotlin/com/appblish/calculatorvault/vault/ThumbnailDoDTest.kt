package com.appblish.calculatorvault.vault

import android.graphics.Color
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board-added Phase-1 check #1 (APP-237/APP-241): **thumbnails actually render in the
 * vault grids** — no star/play placeholders, no blank grey squares. The grid tiles call
 * exactly one seam for their pixels, [CategoryViewModel.thumbnail] (see
 * `CategoryScreen`'s `loadThumbnail`/`loadCover`), and render the placeholder glyph iff
 * it returns null — so this proves, at that seam, that a hidden photo decodes back to the
 * *source image's own pixels* and a hidden video decodes to a real first frame, both from
 * the encrypted blobs.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_thumbs"
    private val imageName = "calcvault_dod_thumb_${System.nanoTime()}.jpg"
    private val videoName = "calcvault_dod_thumb_${System.nanoTime()}.mp4"
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
        DoDTestSupport.deleteImageRows(context, imageName)
        DoDTestSupport.deleteVideoRows(context, videoName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun hiddenPhotoAndVideoDecodeRealGridThumbnailsNotPlaceholders() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // Hide a solid-green photo and a genuine synthesized solid-red MP4.
            val imageUri =
                DoDTestSupport.insertPublicImage(context, imageName, relativePath, DoDTestSupport.sampleJpegBytes())
            val videoUri =
                DoDTestSupport.insertPublicVideo(
                    context,
                    videoName,
                    "Movies/CalcVaultDoD/",
                    DoDTestSupport.synthesizeMp4Bytes(context),
                )
            val stored =
                repo.hide(
                    listOf(
                        stagedItem("staged-img", VaultCategory.PHOTOS, imageName, imageUri.toString(), "image/jpeg"),
                        stagedItem("staged-vid", VaultCategory.VIDEOS, videoName, videoUri.toString(), "video/mp4"),
                    ),
                )
            assertThat(stored).hasSize(2)
            val storedImage = stored.single { it.category == VaultCategory.PHOTOS }
            val storedVideo = stored.single { it.category == VaultCategory.VIDEOS }

            // Photo tile: the exact ViewModel seam the grid calls returns the image's own
            // pixels (solid green), not null (which is what renders the placeholder glyph).
            val photosVm = CategoryViewModel(VaultCategory.PHOTOS, repository = repo)
            photosVm.state.first { state -> state.items.any { it.id == storedImage.id } }
            val photoThumb = photosVm.thumbnail(context, storedImage.id)
            assertThat(photoThumb).isNotNull()
            val photoBitmap = photoThumb!!.asAndroidBitmap()
            assertThat(photoBitmap.width).isGreaterThan(0)
            assertThat(photoBitmap.height).isGreaterThan(0)
            val photoPixel = photoBitmap.getPixel(photoBitmap.width / 2, photoBitmap.height / 2)
            // sampleJpegBytes paints rgb(20, 180, 90); JPEG is lossy so assert the hue, not
            // exact bytes: green channel dominates both others by a wide margin.
            assertThat(Color.green(photoPixel)).isGreaterThan(Color.red(photoPixel) + 60)
            assertThat(Color.green(photoPixel)).isGreaterThan(Color.blue(photoPixel) + 60)

            // Video tile: a real decoded frame from the encrypted blob (solid red), not
            // null — so the grid shows a frame, never a play-glyph placeholder tile.
            val videosVm = CategoryViewModel(VaultCategory.VIDEOS, repository = repo)
            videosVm.state.first { state -> state.items.any { it.id == storedVideo.id } }
            val videoThumb = videosVm.thumbnail(context, storedVideo.id)
            assertThat(videoThumb).isNotNull()
            val videoBitmap = videoThumb!!.asAndroidBitmap()
            assertThat(videoBitmap.width).isGreaterThan(0)
            val videoPixel = videoBitmap.getPixel(videoBitmap.width / 2, videoBitmap.height / 2)
            // Encoded solid red (Y=76 U=84 V=255): red channel dominates after the codec
            // round-trip.
            assertThat(Color.red(videoPixel)).isGreaterThan(Color.green(videoPixel) + 60)
            assertThat(Color.red(videoPixel)).isGreaterThan(Color.blue(videoPixel) + 60)
        }

    private fun stagedItem(
        id: String,
        category: VaultCategory,
        name: String,
        sourceUri: String,
        mimeType: String,
    ): VaultItem =
        VaultItem(
            id = id,
            category = category,
            originalName = name,
            dateLabel = "Today",
            sortKey = System.currentTimeMillis(),
            sourceUri = sourceUri,
            mimeType = mimeType,
            relativePath = relativePath,
        )
}
