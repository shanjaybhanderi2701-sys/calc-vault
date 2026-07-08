package com.appblish.calculatorvault.vault.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The APP-294 "verifyNoEgress-style" static gate: Share must add **zero network egress**
 * and its FileProvider must expose **only** the scoped share cache subtree. Phase 1 is a
 * fully offline app (APP-227 scope guard — no INTERNET permission declared), and the one
 * feature that decrypts vault content for another app must keep it that way. These
 * checks parse the real manifest/provider-paths sources so any future edit that widens
 * them fails CI, not security review.
 */
class ShareNoEgressTest {
    private val manifest = mainSourceFile("AndroidManifest.xml")
    private val sharePaths = mainSourceFile("res/xml/vault_share_paths.xml")

    @Test
    fun `manifest declares no network permissions`() {
        val text = manifest.readText()
        NETWORK_PERMISSIONS.forEach { permission ->
            assertThat(text).doesNotContain("android.permission.$permission\"")
        }
    }

    @Test
    fun `share provider is not exported and grants per-uri only`() {
        val provider = providerElement()
        assertThat(provider).contains("androidx.core.content.FileProvider")
        assertThat(provider).contains("android:exported=\"false\"")
        assertThat(provider).contains("android:grantUriPermissions=\"true\"")
        assertThat(provider).contains("@xml/vault_share_paths")
    }

    @Test
    fun `provider paths whitelist exactly the share cache subtree`() {
        val text = sharePaths.readText()
        // Only cache-path entries (app-private cacheDir) — never external-path,
        // files-path, or root-path, which could reach the vault or public storage.
        val entries = Regex("<(\\w+-path)\\b[^>]*>").findAll(text).map { it.groupValues[1] }.toList()
        assertThat(entries).containsExactly("cache-path")
        assertThat(text).contains("path=\"${VaultShare.SHARE_DIR}/\"")
    }

    /** The `<provider …>` element's full text, located structurally (not line-based). */
    private fun providerElement(): String {
        val text = manifest.readText()
        val start = text.indexOf("<provider")
        assertThat(start).isAtLeast(0)
        return text.substring(start, text.indexOf("</provider>", start))
    }

    private companion object {
        val NETWORK_PERMISSIONS =
            listOf(
                "INTERNET",
                "ACCESS_NETWORK_STATE",
                "ACCESS_WIFI_STATE",
                "CHANGE_NETWORK_STATE",
                "CHANGE_WIFI_STATE",
            )

        /**
         * Resolve a file under `src/main/` whether the JVM test runs from the module dir
         * (Gradle default) or the repo root (some IDE runners).
         */
        fun mainSourceFile(relative: String): File {
            val candidates =
                listOf(
                    File("src/main/$relative"),
                    File("app/src/main/$relative"),
                )
            return candidates.firstOrNull { it.exists() }
                ?: error("Cannot locate src/main/$relative from ${File("").absolutePath}")
        }
    }
}
