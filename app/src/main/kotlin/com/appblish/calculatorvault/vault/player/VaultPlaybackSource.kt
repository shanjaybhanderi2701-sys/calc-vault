package com.appblish.calculatorvault.vault.player

import com.appblish.calculatorvault.vault.crypto.VaultCrypto
import java.io.File

/**
 * Everything [EncryptedVaultDataSource] needs to stream one encrypted vault video, resolved
 * **off the main thread** by the ViewModel from an opaque item id before the ExoPlayer
 * media source is built: the on-disk AES-GCM [blob] and the current unlocked session's
 * [crypto] (which holds the key — the key never leaves [VaultCrypto], is never logged, and
 * never crosses the `MediaItem` URI). Bound to the session that produced it; re-resolve
 * after a lock/unlock (decoy switch) rather than caching across sessions.
 */
data class VaultPlaybackSource(
    val blob: File,
    val crypto: VaultCrypto,
)
