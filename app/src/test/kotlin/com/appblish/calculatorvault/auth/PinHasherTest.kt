package com.appblish.calculatorvault.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinHasherTest {
    @Test
    fun `hash then verify accepts the same secret`() {
        val token = PinHasher.hash("1984")
        assertThat(PinHasher.verify("1984", token)).isTrue()
    }

    @Test
    fun `verify rejects a different secret`() {
        val token = PinHasher.hash("1984")
        assertThat(PinHasher.verify("1985", token)).isFalse()
    }

    @Test
    fun `same secret hashes to different tokens because of the random salt`() {
        val a = PinHasher.hash("0000")
        val b = PinHasher.hash("0000")
        assertThat(a).isNotEqualTo(b)
        // ...yet both still verify.
        assertThat(PinHasher.verify("0000", a)).isTrue()
        assertThat(PinHasher.verify("0000", b)).isTrue()
    }

    @Test
    fun `token never contains the cleartext secret`() {
        val token = PinHasher.hash("4242")
        assertThat(token).doesNotContain("4242")
    }

    @Test
    fun `verify returns false for a malformed token instead of throwing`() {
        assertThat(PinHasher.verify("1234", "not-a-real-token")).isFalse()
        assertThat(PinHasher.verify("1234", "100000:zz:zz")).isFalse()
        assertThat(PinHasher.verify("1234", "")).isFalse()
    }
}
