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

    private val collisionName = "calcvault_collision_${System.nanoTime()}.jpg"

    @After
    fun cleanup() {
        // Leave the gallery as we found it: drop the probe row and every row the collision
        // test published into its dedicated folder (original + `(1)` suffix).
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/CalcVaultCollisionTest/%"),
        )
    }

    /** All DISPLAY_NAMEs MediaStore.Images currently indexes under [relPath]. */
    private fun displayNamesIn(relPath: String): List<String> =
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relPath),
                null,
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                buildList { while (c.moveToNext()) add(c.getString(col)) }
            } ?: emptyList()

    private fun solidJpegBytes(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
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
     * APP-303 on-device collision spot-check (the residual the APP-302 gate could not capture
     * because a concurrent run on emulator-5554 corrupted the fixture): un-hide the *same*
     * display name into a folder that already holds a file of that name and confirm the
     * restore lands as `NAME (1).jpg` **without clobbering** the pre-existing file.
     *
     * This exercises the real device path — MediaStore uniquifies a colliding DISPLAY_NAME
     * on API 29+ (the JVM [UniqueChildNameTest] pins the legacy in-app suffix logic). Runs
     * on the dedicated-AVD CI matrix, so it is the deterministic version of the manual
     * spot-check the acceptance bar asked for.
     */
    @Test
    fun unhideCollisionSuffixesRestoredFileAndKeepsExistingIntact() {
        val name = collisionName
        val folder = "DCIM/CalcVaultCollisionTest/"

        fun itemFor(bytesColor: Int) =
            VaultItem(
                id = "collision",
                category = VaultCategory.PHOTOS,
                originalName = name,
                dateLabel = "Today",
                sortKey = 0L,
                mimeType = "image/jpeg",
                relativePath = folder,
            ) to solidJpegBytes(bytesColor)

        val sink = MediaSink(context)

        // 1) Pre-place the original (green) file in the target folder.
        val (firstItem, greenBytes) = itemFor(Color.rgb(20, 180, 90))
        val firstUri = sink.writeBack(firstItem, greenBytes)
        assertNotNull("pre-existing file did not reach public storage", firstUri)

        // 2) Un-hide a *different* (red) file with the SAME display name into the SAME folder.
        val (secondItem, redBytes) = itemFor(Color.rgb(200, 40, 40))
        val secondUri = sink.writeBack(secondItem, redBytes)
        assertNotNull("collision write-back returned null", secondUri)

        // 3) Two distinct MediaStore rows — the second did not overwrite the first.
        assertTrue("collision reused the same row — existing file was clobbered", firstUri != secondUri)

        // 4) The pre-existing file's bytes are intact (still green, not the red overwrite).
        val firstReadBack =
            context.contentResolver.openInputStream(firstUri!!)?.use { it.readBytes() } ?: ByteArray(0)
        assertTrue("pre-existing file was clobbered by the collision write", firstReadBack.contentEquals(greenBytes))

        // 5) The folder now holds exactly the original name AND the `(1)`-suffixed restore.
        val names = displayNamesIn(folder)
        assertTrue("original name missing after collision restore: $names", names.contains(name))
        val suffixed = name.replace(".jpg", " (1).jpg")
        assertTrue("restored file was not suffixed to '$suffixed' (got $names)", names.contains(suffixed))
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
