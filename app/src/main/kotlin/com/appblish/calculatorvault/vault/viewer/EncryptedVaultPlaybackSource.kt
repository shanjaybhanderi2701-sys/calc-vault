package com.appblish.calculatorvault.vault.viewer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.appblish.calculatorvault.vault.crypto.DecryptingBlobReader
import com.appblish.playerkit.PlaybackSource

/**
 * CalcVault's encrypted [PlaybackSource] — the app-specific concrete source that plugs into the
 * shared player-kit seam (APP-413).
 *
 * The opaque `vault://item/<itemId>` uri carries only an identity; the actual bytes are unsealed
 * on demand by [EncryptedVaultDataSource], so no plaintext or key material ever touches disk or
 * crosses the `MediaItem`/uri boundary (§1.1). The player surface never learns where the bytes
 * live — it consumes this [PlaybackSource] exactly like the gallery's plain-file source.
 */
@UnstableApi
class EncryptedVaultPlaybackSource(
    itemId: String,
    private val openReader: (itemId: String) -> DecryptingBlobReader?,
) : PlaybackSource {
    override val uri: Uri = EncryptedVaultDataSource.vaultMediaUri(itemId)
    override fun dataSourceFactory(): DataSource.Factory = EncryptedVaultDataSource.Factory(openReader)
}
