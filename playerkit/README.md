# player-kit

Shared **Android video-player kit** (Jetpack Compose + Media3/ExoPlayer) for appblish apps.

This repository is the **single canonical source** of the app-agnostic player core. It is consumed by
two apps via `git subtree` (home decision: JGallery issue **APP-409**):

| App       | Consumes at prefix   | Concrete source it plugs in                                   |
|-----------|----------------------|--------------------------------------------------------------|
| JGallery  | `core/playerkit/`    | `StorageAccessDataSource.Factory` (plain local file, §1.6)   |
| CalcVault | `playerkit/`         | `EncryptedVaultDataSource.Factory` (decrypt-on-demand blob)  |

## The seam

The player surface never learns where an item's bytes live — it consumes a **`PlaybackSource`**:

```kotlin
interface PlaybackSource {
    val uri: Uri                            // opaque identity only (no path/key material)
    fun dataSourceFactory(): DataSource.Factory
}
```

Every concrete source is just a Media3 `DataSource.Factory` behind an opaque `uri`. Because the served
byte stream is byte-for-byte the original file in both cases, every ExoPlayer extractor/codec works
unchanged and the surface is identical for both apps. **Build once, plug in either source.**

## Contents

- `PlaybackSource.kt` — the pluggable seam + `progressiveMediaSource()` helper.
- `VideoPlayerSurface.kt` / `VideoPlayerControls.kt` / `VideoPlayerGestures.kt` — the Compose surface.
- `VideoGestureMath.kt` / `VideoScaleMath.kt` / `VideoZoomMath.kt` / `VideoZoomState.kt` — pure-JVM
  gesture / aspect-fit / pinch-zoom math, unit-tested (29 JVM tests).

## Consuming via git subtree

```bash
# First time (JGallery)
git subtree add  --prefix=core/playerkit  <player-kit-remote> main --squash
# First time (CalcVault)
git subtree add  --prefix=playerkit       <player-kit-remote> main --squash

# Pull later updates
git subtree pull --prefix=<prefix> <player-kit-remote> main --squash

# Push local edits made inside the prefix back up to this canonical repo
git subtree push --prefix=<prefix> <player-kit-remote> main
```

Then add `include(":playerkit")` (CalcVault) / `include(":core:playerkit")` (JGallery) to
`settings.gradle.kts` and depend on it (`implementation(project(...))`).

## Design decision

Home = **git subtree from this repo** (option B). Published/versioned AAR (option A) is the documented
north-star migration, triggered by a third consumer, rising release cadence, or board-provisioned
`packages:write` credentials. See JGallery APP-409 `decision` document.
