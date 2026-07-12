package com.appblish.playerkit

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * The **pluggable playback seam** for the shared player kit (APP-402).
 *
 * The player surface never learns where an item's bytes live — it consumes a [PlaybackSource].
 * Every concrete source is just a Media3 [DataSource.Factory] behind an opaque [uri] that carries
 * only an identity (never a real file path or key). The two shipping implementations prove the
 * abstraction:
 *
 *  - **The gallery app** — a plain-file source: `StorageAccessDataSource.Factory` streams local
 *    bytes through the §1.6 storage boundary (`core:playback`).
 *  - **The secure-media app** — a decode-on-read source whose factory unseals blobs on demand, so no
 *    plaintext ever touches disk (that logic stays in the secure app).
 *
 * Because the served byte stream is byte-for-byte the original file in both cases, every ExoPlayer
 * extractor/codec works unchanged and the surface is identical for both apps. Build once, plug in
 * either source.
 */
interface PlaybackSource {

    /**
     * Opaque per-item uri handed to Media3 for player/media-session identity. It is synthetic:
     * the bound [DataSource] never resolves it — resolution happens inside the factory, so no path
     * or key material crosses the `MediaItem`/uri boundary.
     */
    val uri: Uri

    /**
     * The pluggable byte source. ExoPlayer drives it on its loading thread (never the UI thread);
     * seeks reopen at a new position. Plain-file and decrypt-on-demand implementations differ only
     * here.
     */
    fun dataSourceFactory(): DataSource.Factory
}

/**
 * Wraps a [PlaybackSource] into the progressive [MediaSource] the surface hands to `ExoPlayer`.
 * Progressive covers the single-file local/sealed case both apps use; a source that needs adaptive
 * streaming can supply its own builder without touching the seam.
 */
@OptIn(UnstableApi::class)
fun PlaybackSource.progressiveMediaSource(): MediaSource =
    ProgressiveMediaSource.Factory(dataSourceFactory())
        .createMediaSource(MediaItem.fromUri(uri))
