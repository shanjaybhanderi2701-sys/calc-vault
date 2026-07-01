package com.appblish.calculatorvault.calculator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecretCodeDetectorTest {
    @Test
    fun `matches configured secret`() {
        val detector = SecretCodeDetector(secretProvider = { "1984" })
        assertThat(detector.isUnlockTrigger("1984")).isTrue()
    }

    @Test
    fun `ignores non-matching input`() {
        val detector = SecretCodeDetector(secretProvider = { "1984" })
        assertThat(detector.isUnlockTrigger("1985")).isFalse()
    }

    @Test
    fun `no secret configured never triggers`() {
        val detector = SecretCodeDetector(secretProvider = { null })
        assertThat(detector.isUnlockTrigger("1984")).isFalse()
    }

    @Test
    fun `blank secret never triggers`() {
        val detector = SecretCodeDetector(secretProvider = { "   " })
        assertThat(detector.isUnlockTrigger("   ")).isFalse()
    }
}
