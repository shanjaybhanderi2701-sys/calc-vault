package com.appblish.calculatorvault.vault

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.appblish.calculatorvault.vault.share.VaultShare
import com.appblish.calculatorvault.vault.storage.VaultStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * APP-533 §4/§5 instrumented DoD — the security-critical Documents **view hand-off** (feeds
 * the APP-534 Security gate). A hidden document opens in a real installed viewer via a
 * single-item `ACTION_VIEW` on the reviewed Share FileProvider, and its decrypted copy is
 * purged the moment the viewer returns. This proves the invariants the reviewer must confirm:
 *
 *  1. hide() encrypts a [VaultCategory.FILES] document through the shared path — the blob is
 *     real ciphertext, the source is consumed via its `content://`/`file://` URI (no media
 *     poster/thumbnail work);
 *  2. [VaultShare.viewIntent] is an `ACTION_VIEW` chooser whose data URI is the opaque
 *     FileProvider URI (right authority), typed by the document MIME, that round-trips the
 *     plaintext — and never encodes the vault's UUID blob name or the `.CalcVault/` path;
 *  3. the view grant is **read-only**, never write;
 *  4. **purge on return** removes the temp copy and kills the URI (guaranteed cleanup), with
 *     [VaultShare.purgeAll] sweeping crash leftovers at process restart.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsHandoffDoDTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "dod_documents"

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        DoDTestSupport.grantAllFilesAccess(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultShare.purgeAll(context)
        VaultSession.begin("1234", namespace = namespace)
    }

    @After
    fun cleanUp() {
        VaultShare.purgeAll(context)
        DoDTestSupport.deleteNamespace(namespace)
        VaultSession.clear()
    }

    @Test
    fun documentViewIntentIsReadOnlyScopedAndPurgedOnReturn() =
        runBlocking {
            val repo = unlockedRepo()
            // A PDF's real bytes; %PDF magic so we can prove the blob is ciphertext, not this.
            val original = "%PDF-1.7\nDoD document body\n%%EOF".toByteArray()
            val stored = hideDocument(repo, "Q3 report.pdf", "application/pdf", original)

            // (1) The stored blob is real ciphertext on disk — never the plaintext PDF.
            assertThat(stored.category).isEqualTo(VaultCategory.FILES)
            val blob = VaultStorage.blobFile(context, stored.encryptedPath!!)
            assertThat(blob.exists()).isTrue()
            blob.inputStream().use { stream ->
                val head = ByteArray(4)
                assertThat(stream.read(head)).isEqualTo(4)
                assertThat(head).isNotEqualTo("%PDF".toByteArray())
            }

            // Prepare the single-item view session (same reviewed decrypt-to-temp path as Share).
            val session = VaultShare.prepare(context, repo, listOf(stored))
            assertThat(session).isNotNull()
            session!!

            // (2) Scoped temp copy under cacheDir/share/<uuid>/, carrying the display name.
            val shareRoot = File(context.cacheDir, "share")
            assertThat(session.dir.parentFile).isEqualTo(shareRoot)
            val copy = File(session.dir, "Q3 report.pdf")
            assertThat(copy.exists()).isTrue()
            assertThat(copy.readBytes()).isEqualTo(original)

            val chooser = VaultShare.viewIntent(session)
            assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
            val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertThat(view.action).isEqualTo(Intent.ACTION_VIEW)

            // (2) Opaque provider URI, typed by the document MIME, round-tripping the plaintext;
            //     no vault internals (blob UUID / .CalcVault) leak into the URI.
            val uri = view.data!!
            assertThat(uri.scheme).isEqualTo("content")
            assertThat(uri.authority).isEqualTo("${context.packageName}.share")
            assertThat(view.type).isEqualTo("application/pdf")
            assertThat(uri.toString()).doesNotContain(stored.encryptedPath!!)
            assertThat(uri.toString()).doesNotContain(".CalcVault")
            val served = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertThat(served).isEqualTo(original)

            // (3) Read-only grant on both the chooser and the wrapped VIEW intent, never write.
            assertThat(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            assertThat(view.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
            assertThat(chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
            assertThat(view.clipData!!.itemCount).isEqualTo(1)

            // (4) Purge on return: the temp copy is gone and the URI dies with it.
            VaultShare.purge(session)
            assertThat(session.dir.exists()).isFalse()
            assertThat(runCatching { context.contentResolver.openInputStream(uri) }.exceptionOrNull())
                .isInstanceOf(FileNotFoundException::class.java)
        }

    @Test
    fun viewIntentRejectsAMultiItemSession() =
        runBlocking {
            val repo = unlockedRepo()
            val a = hideDocument(repo, "a.pdf", "application/pdf", "%PDF a".toByteArray())
            val b = hideDocument(repo, "b.pdf", "application/pdf", "%PDF b".toByteArray())
            val session = VaultShare.prepare(context, repo, listOf(a, b))!!
            // A document is opened one at a time — a batched session is a programming error.
            assertThat(runCatching { VaultShare.viewIntent(session) }.isFailure).isTrue()
            VaultShare.purge(session)
        }

    // --- fixtures ----------------------------------------------------------------------

    private fun unlockedRepo(): EncryptedVaultContentRepository {
        val repo = EncryptedVaultContentRepository(context)
        repo.unlock()
        DoDTestSupport.awaitUnlock(repo)
        return repo
    }

    /** Stage [bytes] as a cache file and hide it as a Documents item, returning the stored row. */
    private suspend fun hideDocument(
        repo: EncryptedVaultContentRepository,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): VaultItem {
        val staged = File(context.cacheDir, "staged_doc_${System.nanoTime()}.bin")
        staged.writeBytes(bytes)
        return repo
            .hide(
                listOf(
                    VaultItem(
                        id = "",
                        category = VaultCategory.FILES,
                        originalName = displayName,
                        dateLabel = "Today",
                        sortKey = System.currentTimeMillis(),
                        sourceUri = "file://${staged.absolutePath}",
                        mimeType = mimeType,
                    ),
                ),
            ).single()
            .also { staged.delete() }
    }
}
