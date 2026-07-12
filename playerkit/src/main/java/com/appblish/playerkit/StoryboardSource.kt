package com.appblish.playerkit

/**
 * The **pluggable scrub-preview seam** for the shared player kit (APP-438, Decision 2).
 *
 * It mirrors [PlaybackSource] exactly, one layer up: the player surface never learns where a video's
 * preview frames come from — it consumes a [StoryboardSource] and asks it for one already-encoded
 * [VideoStoryboard] strip. The kit then decodes + caches that strip ([VideoStoryboardCache]) and
 * crops the nearest frame per scrub tick, with no re-acquisition. Frame acquisition (extracting
 * frames from a plain `content://` via `MediaMetadataRetriever`, or handing back a pre-generated
 * strip that a secure app sealed at hide-time) stays entirely app-side.
 *
 * The two shipping implementations prove the abstraction:
 *  - **The gallery app** — a trivial plain-file source: `MediaMetadataRetriever.getFrameAtTime` over
 *    the plain `content://`, then [VideoStoryboard.encode] on demand.
 *  - **The secure-media app** — returns the decoded bytes of the strip it produced at hide-time from
 *    the still-readable plaintext and sealed beside the blob. All lifecycle + protection logic stays
 *    in that app; only the plain strip bytes cross this seam.
 *
 * A surface given a `null` [StoryboardSource] simply has no scrub preview (the seekbar shows only its
 * time-code bubble). Symmetric with `PlaybackSource`, one mental model for both features.
 */
interface StoryboardSource {

    /**
     * The encoded storyboard strip for this item (the [VideoStoryboard] container bytes), or `null`
     * when no preview is available — a video that was never storyboarded, or an acquisition failure.
     * Called at most once per surface (the kit caches the decoded result); runs off the UI thread.
     */
    suspend fun loadStrip(): ByteArray?
}
