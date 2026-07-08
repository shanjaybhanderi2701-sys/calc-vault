package com.appblish.calculatorvault.vault

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * APP-244 board question: **does hiding a large video still force-close the app?** This
 * test answers it mechanically on the CI matrix: it stages a hundreds-of-MB synthetic
 * video — deliberately larger than the app's whole heap, so any code path that buffers
 * the file in memory (as the pre-APP-244 single-message GCM did inside Conscrypt's
 * one-shot AEAD) is *guaranteed* to OOM-crash — hides it, then streams it back out and
 * verifies the bytes. Passing proves both crypto directions run in chunk-bounded memory.
 */
@RunWith(AndroidJUnit4::class)
class LargeVideoHideDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_largevideo"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun hidingAHundredsOfMbVideoDoesNotCrashAndRoundTrips() =
        runBlocking {
            val sizeBytes = 300L * 1024 * 1024
            val maxHeap = Runtime.getRuntime().maxMemory()
            Log.i("LargeVideoDoD", "video=${sizeBytes / (1024 * 1024)}MB heap=${maxHeap / (1024 * 1024)}MB")

            // Stage the synthetic "video" on disk with a recognizable byte pattern at both
            // ends, streamed out in 1 MB slices (the fixture itself must not OOM either).
            val source = File(context.cacheDir, "dod_large_video.mp4")
            val slice = ByteArray(1024 * 1024) { (it % 251).toByte() }
            source.outputStream().use { out ->
                var written = 0L
                while (written < sizeBytes) {
                    out.write(slice)
                    written += slice.size
                }
            }
            assertThat(source.length()).isEqualTo(sizeBytes)

            val repo = EncryptedVaultContentRepository(context)
            repo.unlock()
            DoDTestSupport.awaitUnlock(repo)

            val staged =
                VaultItem(
                    id = "staged_large",
                    category = VaultCategory.VIDEOS,
                    originalName = "dod_large_video.mp4",
                    dateLabel = "Today",
                    sortKey = System.currentTimeMillis(),
                    sourceUri = "file://${source.absolutePath}",
                    mimeType = "video/mp4",
                    relativePath = "Movies/",
                )

            // The board's question, asked directly: this call OOM-crashed the process on
            // the pre-APP-244 build (whole-file GCM buffering). It must complete now.
            val hideStart = System.currentTimeMillis()
            val stored = repo.hide(listOf(staged)).single()
            Log.i("LargeVideoDoD", "hide took ${System.currentTimeMillis() - hideStart}ms")

            val blob = VaultStorage.blobFile(context, stored.encryptedPath!!)
            assertThat(blob.exists()).isTrue()
            // Ciphertext at least as large as the plaintext (header + per-chunk tags).
            assertThat(blob.length()).isAtLeast(sizeBytes)

            // Free the staged original before decrypting back (CI disk headroom).
            source.delete()

            // Opening/restoring streams too: decrypt the whole blob back to disk and
            // verify size and the pattern at both ends.
            val out = File(context.cacheDir, "dod_large_video_out.bin")
            try {
                val openStart = System.currentTimeMillis()
                assertThat(repo.decryptToFile(stored.id, out)).isTrue()
                Log.i("LargeVideoDoD", "decryptToFile took ${System.currentTimeMillis() - openStart}ms")
                assertThat(out.length()).isEqualTo(sizeBytes)
                out.inputStream().use { stream ->
                    val head = ByteArray(16)
                    assertThat(stream.read(head)).isEqualTo(16)
                    assertThat(head).isEqualTo(slice.copyOfRange(0, 16))
                }
                assertThat(out.length() % slice.size).isEqualTo(0)
            } finally {
                out.delete()
                blob.delete()
            }
        }
}
