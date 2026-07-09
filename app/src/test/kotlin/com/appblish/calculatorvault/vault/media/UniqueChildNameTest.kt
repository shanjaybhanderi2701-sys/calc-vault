package com.appblish.calculatorvault.vault.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-303 (APP-302 residual): pin the spec §2.4 name-collision suffix rule that the
 * legacy-file un-hide route uses (`MediaSink.uniqueFile` → [uniqueChildName]). Before this,
 * the `IMG.jpg → IMG (1).jpg → IMG (2).jpg` behaviour had an impl comment but no test
 * asserted it, so a regression that clobbered an existing same-named file (or mangled the
 * extension) would have shipped silently.
 *
 * The collision probe is injected as a `Set<String>` of already-taken names, so these are
 * deterministic JVM tests — no device, no filesystem.
 */
class UniqueChildNameTest {
    private fun taken(vararg names: String): (String) -> Boolean = names.toSet()::contains

    @Test
    fun `a free name is returned unchanged`() {
        assertThat(uniqueChildName("IMG.jpg", taken())).isEqualTo("IMG.jpg")
    }

    @Test
    fun `a collision gets the (1) suffix before the extension`() {
        assertThat(uniqueChildName("IMG.jpg", taken("IMG.jpg"))).isEqualTo("IMG (1).jpg")
    }

    @Test
    fun `consecutive collisions walk to the first free index`() {
        val exists = taken("IMG.jpg", "IMG (1).jpg", "IMG (2).jpg")
        assertThat(uniqueChildName("IMG.jpg", exists)).isEqualTo("IMG (3).jpg")
    }

    @Test
    fun `a gap in the sequence is filled by the lowest free index`() {
        // (1) is taken but (2) is free — we must not skip to (3) and we must not clobber (1).
        val exists = taken("IMG.jpg", "IMG (1).jpg")
        assertThat(uniqueChildName("IMG.jpg", exists)).isEqualTo("IMG (2).jpg")
    }

    @Test
    fun `an extensionless name suffixes at the end`() {
        assertThat(uniqueChildName("README", taken("README"))).isEqualTo("README (1)")
    }

    @Test
    fun `a multi-dot name only splits on the final extension`() {
        assertThat(uniqueChildName("archive.tar.gz", taken("archive.tar.gz")))
            .isEqualTo("archive.tar (1).gz")
    }

    @Test
    fun `a leading-dot file is treated as all stem, never mangling the dot`() {
        // lastIndexOf('.') == 0 → not an extension; suffix goes after the whole name.
        assertThat(uniqueChildName(".env", taken(".env"))).isEqualTo(".env (1)")
    }

    @Test
    fun `the pre-existing colliding file is never returned (not clobbered)`() {
        val original = "vacation.png"
        val result = uniqueChildName(original, taken(original, "vacation (1).png"))
        assertThat(result).isNotEqualTo(original)
        assertThat(result).isEqualTo("vacation (2).png")
    }
}
