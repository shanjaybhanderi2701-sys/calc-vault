package com.appblish.calculatorvault.vault.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-303 (residual of APP-302 P0-2) — the on-device chosen-folder unhide passed the gate,
 * but the name-collision `(1)` suffix ([MediaSink.uniqueFile] via [uniqueChildName],
 * MediaSink.kt spec §2.4) had an impl comment and **no test asserting it**. This pins the
 * in-app collision contract: restoring a file whose display name already exists in the
 * target folder must land as `NAME (1).ext`, never clobber the existing file, and keep
 * counting up for repeated collisions.
 *
 * Pure JVM test (no device/filesystem): [exists] is modelled by a name set so the suffix
 * logic is exercised deterministically off the shared emulator.
 */
class UniqueChildNameTest {
    private fun taken(vararg names: String): (String) -> Boolean {
        val set = names.toSet()
        return { it in set }
    }

    @Test
    fun `no collision returns the original name unchanged`() {
        assertThat(uniqueChildName("IMG.jpg", taken())).isEqualTo("IMG.jpg")
    }

    @Test
    fun `first collision inserts (1) before the extension`() {
        assertThat(uniqueChildName("IMG.jpg", taken("IMG.jpg"))).isEqualTo("IMG (1).jpg")
    }

    @Test
    fun `existing file is never chosen as the target`() {
        // The whole point of the guard: the pre-existing IMG.jpg must not be returned.
        assertThat(uniqueChildName("IMG.jpg", taken("IMG.jpg"))).isNotEqualTo("IMG.jpg")
    }

    @Test
    fun `second collision counts up to (2)`() {
        assertThat(uniqueChildName("IMG.jpg", taken("IMG.jpg", "IMG (1).jpg")))
            .isEqualTo("IMG (2).jpg")
    }

    @Test
    fun `collision skips over gaps to the first free suffix`() {
        // (1) is taken but (2) is free — the loop must return (2), not (3).
        assertThat(uniqueChildName("IMG.jpg", taken("IMG.jpg", "IMG (1).jpg", "IMG (3).jpg")))
            .isEqualTo("IMG (2).jpg")
    }

    @Test
    fun `extension-less name is suffixed at the end`() {
        assertThat(uniqueChildName("README", taken("README"))).isEqualTo("README (1)")
    }

    @Test
    fun `multi-dot name only splits on the final dot`() {
        assertThat(uniqueChildName("archive.tar.gz", taken("archive.tar.gz")))
            .isEqualTo("archive.tar (1).gz")
    }

    @Test
    fun `leading-dot name has no real extension so it is suffixed at the end`() {
        // lastIndexOf('.') == 0, which is not treated as an extension separator.
        assertThat(uniqueChildName(".nomedia", taken(".nomedia"))).isEqualTo(".nomedia (1)")
    }
}
