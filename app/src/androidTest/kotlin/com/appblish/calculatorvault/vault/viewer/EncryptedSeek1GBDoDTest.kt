package com.appblish.calculatorvault.vault.viewer

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * APP-348 W1-QA — the make-or-break §9 DoD at **true 1 GB+ scale**: an arbitrary-offset seek
 * into a 1 GB+ encrypted vault video must be fast and decrypt *from the offset* — never a
 * whole-file decrypt, never a plaintext temp file, never OOM.
 *
 * This drives the **exact production reader** the video DataSource uses
 * ([EncryptedVaultContentRepository.openBlobReader] → `V2ChunkedBlobReader`, the same object
 * [EncryptedVaultDataSource] pulls from on ExoPlayer's loader thread), over a genuinely hidden
 * 1 GB+ vault blob produced by the real [EncryptedVaultContentRepository.hide] encryption path.
 *
 * The source is written with a position-deterministic byte pattern (`byte(p) = p & 0xFF`), so a
 * read after `seekTo(q)` can be asserted **byte-correct** — proving the seek decrypts the right
 * chunk from the offset, not just that it returns quickly.
 *
 * Evidence captured (printed to logcat / instrumentation output):
 *  - `contentLength` derived with **zero decrypt** (open+length wall-time ≪ a full decrypt).
 *  - Seek latency at offsets across the whole 1 GB (0 %, 25 %, 50 %, 75 %, ~100 %, backward):
 *    small and **flat** vs. offset — the signature of O(1) random access (a forward-only
 *    reader would scale with the offset and blow up near the end).
 *  - Byte-correct plaintext at each arbitrary offset.
 *  - The viewer cache dir stays **empty** — no plaintext temp file on disk (§1.1).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSeek1GBDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_seek_1gb"
    private val videoName = "calcvault_dod_1gb_${System.nanoTime()}.mp4"

    // Just over 1 GiB, deliberately NOT chunk-aligned so the short final chunk is exercised too.
    private val totalBytes = 1024L * 1024L * 1024L + 3L * 512L * 1024L

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteVideoRows(context, videoName)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun seekIntoOnePlusGigabyteEncryptedVideoIsFastFromOffsetWithNoPlaintextOnDisk() =
        runBlocking<Unit> {
            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            // 1) Plant a genuine 1 GB+ public source (streamed — never a 1 GB ByteArray in RAM).
            val encStart = System.currentTimeMillis()
            val uri = insertLargePublicVideo(videoName, "Movies/CalcVaultDoD/", totalBytes)

            // 2) Hide it through the REAL encryption path → a v2 chunked AES-256-GCM vault blob.
            val stored =
                repo
                    .hide(
                        listOf(
                            VaultItem(
                                id = "staged-1gb-video",
                                category = VaultCategory.VIDEOS,
                                originalName = videoName,
                                dateLabel = "Today",
                                sortKey = System.currentTimeMillis(),
                                sourceUri = uri.toString(),
                                mimeType = "video/mp4",
                                relativePath = "Movies/CalcVaultDoD/",
                            ),
                        ),
                    ).single()
            val encMs = System.currentTimeMillis() - encStart
            log("hid 1GB+ source + encrypted to vault blob in ${encMs} ms (plaintext=$totalBytes bytes)")

            val viewerCacheDir = File(context.cacheDir, "viewer")
            val cacheBaseline = countFiles(context.cacheDir)

            // 3) Open the EXACT production reader the DataSource uses. Time open + length:
            //    a whole-file decrypt of 1 GB would take seconds; O(1) length is ~instant.
            val openStart = System.nanoTime()
            val reader =
                requireNotNull(repo.openBlobReader(stored.id)) { "openBlobReader returned null for a hidden video" }
            val contentLength = reader.contentLength
            val openMs = (System.nanoTime() - openStart) / 1_000_000.0
            try {
                assertThat(reader.isSeekable).isTrue()
                assertThat(contentLength).isAtLeast(1024L * 1024L * 1024L)
                assertThat(contentLength).isEqualTo(totalBytes)
                log("open+contentLength: ${"%.1f".format(openMs)} ms, length=$contentLength (>=1GiB, seekable=true, ZERO decrypt)")
                // Opening + deriving a 1 GB length must be near-instant (no whole-file decrypt).
                assertThat(openMs).isLessThan(500.0)

                // 4) Seek across the WHOLE 1 GB and measure per-seek latency + byte-correctness.
                val probe = ByteArray(64 * 1024)
                val offsets =
                    longArrayOf(
                        0L,
                        contentLength / 4,
                        contentLength / 2,
                        contentLength * 3 / 4,
                        contentLength - probe.size, // last chunk
                        contentLength / 20, // backward jump to ~5 %
                    )
                val labels = arrayOf("0%", "25%", "50%", "75%", "~100%(end)", "backward->5%")

                val latencies = ArrayList<Long>()
                offsets.forEachIndexed { i, off ->
                    val t0 = System.nanoTime()
                    reader.seekTo(off)
                    var got = 0
                    while (got < probe.size) {
                        val n = reader.read(probe, got, probe.size - got)
                        if (n < 0) break
                        got += n
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000L
                    latencies.add(ms)
                    // Byte-correct decrypt FROM the offset: byte(p) == p & 0xFF.
                    assertThat(got).isGreaterThan(0)
                    for (j in 0 until minOf(got, 4096)) {
                        val expected = ((off + j) and 0xFF).toByte()
                        if (probe[j] != expected) {
                            throw AssertionError(
                                "byte mismatch after seek to ${labels[i]} (offset=$off) at +$j: " +
                                    "expected $expected got ${probe[j]}",
                            )
                        }
                    }
                    log("seek ${labels[i]} (offset=$off): ${ms} ms, read=$got bytes, byte-correct")
                }

                val maxMs = latencies.max()
                val avgMs = latencies.average()
                log("seek latency: avg=${"%.1f".format(avgMs)} ms, max=$maxMs ms across 0..1GiB")

                // O(1) claim: every arbitrary-offset seek is fast, regardless of offset/size.
                // (A forward-only reader would need ~1 GB of skips for the end seek → seconds.)
                assertThat(maxMs).isLessThan(750L)

                // 5) §1.1 — NO plaintext temp file: viewer cache empty; cache file count unchanged.
                val leaked = viewerCacheDir.listFiles()?.toList().orEmpty()
                assertThat(leaked).isEmpty()
                assertThat(countFiles(context.cacheDir)).isAtMost(cacheBaseline)
                log("no-plaintext-on-disk: viewer cache empty, app cache file count unchanged ($cacheBaseline)")
            } finally {
                reader.close()
            }
        }

    /** Stream [totalBytes] of a position-deterministic pattern into a public MediaStore video. */
    private fun insertLargePublicVideo(
        displayName: String,
        relativePath: String,
        totalBytes: Long,
    ): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
                "MediaStore refused the 1GB video insert"
            }
        // A 1 MiB buffer whose byte i == i & 0xFF. Because both the buffer size and every write
        // offset are multiples of 256, absolute byte p always equals p & 0xFF — verifiable after
        // an arbitrary seek — while peak RAM stays at one buffer (no 1 GB array, no OOM).
        val buf = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
        resolver.openOutputStream(uri)!!.use { out ->
            var written = 0L
            while (written < totalBytes) {
                val n = minOf(buf.size.toLong(), totalBytes - written).toInt()
                out.write(buf, 0, n)
                written += n
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile }

    private fun log(msg: String) = android.util.Log.i("Seek1GBDoD", msg)
}
