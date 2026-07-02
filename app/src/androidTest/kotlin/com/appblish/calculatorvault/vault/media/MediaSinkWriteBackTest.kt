package com.appblish.calculatorvault.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * On-device proof of the APP-170 acceptance gate: un-hide writes a vault item's decrypted
 * bytes back to public storage so it *reappears in the system gallery*. We exercise the
 * real [MediaSink.writeBack] against the device MediaStore (no mocks) and then query
 * MediaStore.Images to confirm the OS media index now surfaces the restored file — the
 * board's "watch it return to the gallery" beat.
 */
@RunWith(AndroidJUnit4::class)
class MediaSinkWriteBackTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val displayName = "calcvault_unhide_probe_${System.nanoTime()}.jpg"

    @After
    fun cleanup() {
        // Leave the gallery as we found it: drop the row we published.
        context.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
    }

    @Test
    fun unhideWriteBackPublishesFileIntoTheGallery() {
        val jpeg = sampleJpegBytes()
        val item =
            VaultItem(
                id = "probe",
                category = VaultCategory.PHOTOS,
                originalName = displayName,
                dateLabel = "Today",
                sortKey = 0L,
                mimeType = "image/jpeg",
                // Same album a real hidden photo would carry, so it lands back in DCIM.
                relativePath = "DCIM/CalcVaultRestoreTest/",
            )

        val published = MediaSink(context).writeBack(item, jpeg)

        // 1) The write-back succeeded and handed us a public content Uri.
        assertNotNull("writeBack returned null — file did not reach public storage", published)

        // 2) The OS media index now surfaces the file — i.e. it is back in the gallery.
        val cursor =
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )
        val foundInGallery = cursor?.use { it.moveToFirst() } ?: false
        assertTrue("Restored photo is not indexed in MediaStore.Images (not visible in gallery)", foundInGallery)

        // 3) The bytes actually landed (non-empty), proving a real decrypt→publish, not an empty stub row.
        val readBack =
            context.contentResolver.openInputStream(published!!)?.use { it.readBytes() } ?: ByteArray(0)
        assertTrue("Published file is empty", readBack.isNotEmpty())
    }

    /**
     * Visual-evidence variant (no cleanup): publishes a recognisably-named photo so an
     * adb MediaStore query + a Photos/Files screenshot can show it back in the gallery.
     */
    @Test
    fun demo_leavesRestoredPhotoInGallery() {
        val item =
            VaultItem(
                id = "demo",
                category = VaultCategory.PHOTOS,
                originalName = "CalcVault_Restored_Demo.jpg",
                dateLabel = "Today",
                sortKey = 0L,
                mimeType = "image/jpeg",
                relativePath = "DCIM/Camera/",
            )
        val published = MediaSink(context).writeBack(item, sampleJpegBytes())
        assertNotNull(published)
    }

    private fun sampleJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(20, 180, 90))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
