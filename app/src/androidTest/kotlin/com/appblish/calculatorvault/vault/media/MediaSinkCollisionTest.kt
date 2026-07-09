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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * APP-303 (APP-302 residual #2): on-device proof of the spec §2.4 name-collision contract
 * for un-hide. The gate spot-check could not be captured on the shared emulator (a
 * concurrent instrumented run corrupted the fixture); this pins it deterministically inside
 * the CI instrumented matrix instead — pre-place a same-named file in the target album, then
 * un-hide a second item with the identical display name and assert:
 *
 *  1. the pre-existing file is untouched (its bytes survive), and
 *  2. the restored file lands under a distinct `NAME (1).ext` display name (never clobbered).
 *
 * Both un-hides go through the real [MediaSink.writeBack] against the device MediaStore (no
 * mocks); on API 29+ the collision is uniquified by MediaStore, exactly as it is on a user's
 * device, so this is the end-to-end behaviour the board asked to "watch".
 */
@RunWith(AndroidJUnit4::class)
class MediaSinkCollisionTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    // A per-run album so parallel/other instrumented runs can't collide with this fixture.
    private val album = "DCIM/CalcVaultCollisionTest_${System.nanoTime()}/"
    private val displayName = "collide.jpg"

    @After
    fun cleanup() {
        context.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(album),
        )
    }

    @Test
    fun secondUnhideOfSameNameLandsSuffixedAndDoesNotClobberTheFirst() {
        val sink = MediaSink(context)
        val firstBytes = solidJpeg(Color.rgb(20, 180, 90))
        val secondBytes = solidJpeg(Color.rgb(200, 40, 40))

        val first = sink.writeBack(item(), firstBytes)
        assertNotNull("first writeBack returned null", first)
        val second = sink.writeBack(item(), secondBytes)
        assertNotNull("second writeBack returned null", second)

        // Two distinct rows exist in the album: the original name and its (1) suffix.
        val names = displayNamesInAlbum()
        assertTrue(
            "expected the original '$displayName' to still be present, saw $names",
            names.contains(displayName),
        )
        assertTrue(
            "expected a '(1)'-suffixed sibling, saw $names",
            names.contains("collide (1).jpg"),
        )
        assertEquals("expected exactly two rows in the album, saw $names", 2, names.size)

        // The pre-existing file was not clobbered: its bytes are intact (green, not red).
        val originalBack =
            context.contentResolver.openInputStream(first!!)?.use { it.readBytes() } ?: ByteArray(0)
        assertTrue("original file was emptied/clobbered", originalBack.isNotEmpty())
        assertTrue(
            "original file's bytes changed — it was clobbered by the second un-hide",
            originalBack.contentEquals(firstBytes),
        )
    }

    private fun item() =
        VaultItem(
            id = "collide",
            category = VaultCategory.PHOTOS,
            originalName = displayName,
            dateLabel = "Today",
            sortKey = 0L,
            mimeType = "image/jpeg",
            relativePath = album,
        )

    private fun displayNamesInAlbum(): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(album),
                null,
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) out += c.getString(col)
            }
        return out
    }

    private fun solidJpeg(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
