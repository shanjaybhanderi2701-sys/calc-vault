package com.appblish.calculatorvault.vault

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.storage.StoragePermissions
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Shared harness for the Phase-1 Definition-of-Done instrumented tests (APP-225, build
 * spec §11/§12). The board's hard rule #2 forbids proving the DoD with manual emulator
 * screenshots, so these helpers make each proof mechanical and in-process:
 *
 * - **All Files Access** is self-granted via appops through UiAutomation — the same grant
 *   the OS special-access screen makes and the same technique the approved
 *   survive-uninstall gate ([PublicStorageSurviveUninstallTest]) uses, so CI needs no
 *   adb pre-step before `connectedDebugAndroidTest`.
 * - **MediaStore fixtures** plant a real public image and re-query it afterwards — the
 *   in-process equivalent of the board's "adb content query" evidence.
 * - **Per-namespace cleanup** wipes only the test's own `.CalcVault/<namespace>/`
 *   sub-directory, so tests stay independent and never touch a real vault's root data.
 */
internal object DoDTestSupport {
    /** JPEG SOI magic — a vault blob must never start with it (ciphertext, not a copy). */
    val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** Bare-UUID blob names: 36 chars of hex + dashes, extension stripped. */
    val UUID_REGEX = Regex("[0-9a-fA-F-]{36}")

    /** Grant MANAGE_EXTERNAL_STORAGE the way the OS special-access screen would. */
    fun grantAllFilesAccess(context: Context) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
        )
        // executeShellCommand is async; wait for the grant to take effect.
        repeat(40) { if (StoragePermissions.hasAllFilesAccess(context)) return else Thread.sleep(50) }
        assertThat(StoragePermissions.hasAllFilesAccess(context)).isTrue()
    }

    /** Wait for [repo]'s async unlock (first-run 120k-iteration PBKDF2 is slow when cold). */
    fun awaitUnlock(repo: EncryptedVaultContentRepository) {
        repeat(200) { if (repo.isUnlocked()) return else Thread.sleep(100) }
        assertThat(repo.isUnlocked()).isTrue()
    }

    /** A small real JPEG (solid color) so hidden/restored bytes are a genuine image. */
    fun sampleJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(20, 180, 90))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Insert a public image into MediaStore under [relativePath]; returns its content Uri. */
    fun insertPublicImage(
        context: Context,
        displayName: String,
        relativePath: String,
        bytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertThat(uri).isNotNull()
        resolver.openOutputStream(uri!!)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** The in-process "adb content query": rows MediaStore.Images serves for [displayName]. */
    fun imageRowCount(
        context: Context,
        displayName: String,
    ): Int =
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { it.count } ?: 0

    /** RELATIVE_PATH of the first MediaStore.Images row named [displayName], or null. */
    fun imageRelativePath(
        context: Context,
        displayName: String,
    ): String? =
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Read back the bytes of the first MediaStore.Images row named [displayName]. */
    fun readImageBytes(
        context: Context,
        displayName: String,
    ): ByteArray? {
        val resolver = context.contentResolver
        val id =
            resolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return resolver.openInputStream(uri)?.use { it.readBytes() }
    }

    /** Delete every MediaStore.Images row named [displayName] (cleanup; rows are our own). */
    fun deleteImageRows(
        context: Context,
        displayName: String,
    ) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
            )
        }
    }

    /** Wipe the test's own vault [namespace] under `.CalcVault/` (never the root vault). */
    fun deleteNamespace(namespace: String) {
        require(namespace.isNotBlank()) { "refusing to wipe the root vault namespace" }
        val root = File(Environment.getExternalStorageDirectory(), VaultStorage.DIR_NAME)
        File(root, namespace).deleteRecursively()
    }

    /** Insert a public video into MediaStore under [relativePath]; returns its content Uri. */
    fun insertPublicVideo(
        context: Context,
        displayName: String,
        relativePath: String,
        bytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertThat(uri).isNotNull()
        resolver.openOutputStream(uri!!)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Delete every MediaStore.Video row named [displayName] (cleanup; rows are our own). */
    fun deleteVideoRows(
        context: Context,
        displayName: String,
    ) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
            )
        }
    }

    /**
     * Encode a genuine ~1s solid-red H.264/MP4 in-process (MediaCodec software AVC encoder
     * + MediaMuxer — present on every CI AVD image), so the video thumbnail and Media3
     * playback DoD checks exercise a *real* container/codec instead of a fixture blob that
     * only pretends to be a video.
     */
    fun synthesizeMp4Bytes(
        context: Context,
        width: Int = 320,
        height: Int = 240,
    ): ByteArray {
        val fps = 15
        val frames = 15
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = File.createTempFile("dod_synth", ".mp4", context.cacheDir)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var track = -1
            var muxing = false
            var framesQueued = 0
            var eosSeen = false
            val info = MediaCodec.BufferInfo()
            val frameUs = 1_000_000L / fps
            while (!eosSeen) {
                if (framesQueued <= frames) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (framesQueued == frames) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                framesQueued * frameUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                        } else {
                            val image = codec.getInputImage(inIndex)
                            assertThat(image).isNotNull()
                            fillSolidRedYuv(image!!, width, height)
                            codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, framesQueued * frameUs, 0)
                        }
                        framesQueued++
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null &&
                            info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            muxing
                        ) {
                            muxer.writeSampleData(track, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
        val bytes = out.readBytes()
        out.delete()
        assertThat(bytes.size).isGreaterThan(0)
        return bytes
    }

    /** Fill one flexible-YUV input image with solid red (BT.601: Y=76, U=84, V=255). */
    private fun fillSolidRedYuv(
        image: Image,
        width: Int,
        height: Int,
    ) {
        val values = byteArrayOf(76, 84, 0xFF.toByte())
        image.planes.forEachIndexed { planeIndex, plane ->
            val planeWidth = if (planeIndex == 0) width else width / 2
            val planeHeight = if (planeIndex == 0) height else height / 2
            val buffer = plane.buffer
            for (row in 0 until planeHeight) {
                for (col in 0 until planeWidth) {
                    val position = row * plane.rowStride + col * plane.pixelStride
                    if (position < buffer.capacity()) buffer.put(position, values[planeIndex])
                }
            }
        }
    }
}
