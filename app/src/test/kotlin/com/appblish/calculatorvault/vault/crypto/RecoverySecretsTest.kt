package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.SecureRandom

/** Normalization + generation of the recovery secrets (PIN Recovery spec §2.1 / §2.2). */
class RecoverySecretsTest {
    @Test
    fun `answer normalization trims lowercases and collapses whitespace`() {
        assertThat(RecoverySecrets.normalizeAnswer("  Fluffy   The Cat  ")).isEqualTo("fluffy the cat")
        assertThat(RecoverySecrets.normalizeAnswer("PARIS")).isEqualTo("paris")
        assertThat(RecoverySecrets.normalizeAnswer("Paris")).isEqualTo(RecoverySecrets.normalizeAnswer("paris "))
    }

    @Test
    fun `recovery code normalization strips separators and uppercases`() {
        assertThat(RecoverySecrets.normalizeRecoveryCode("7k9f-2xqp-4mrt-8wvn")).isEqualTo("7K9F2XQP4MRT8WVN")
        assertThat(RecoverySecrets.normalizeRecoveryCode("7K9F 2XQP 4MRT 8WVN")).isEqualTo("7K9F2XQP4MRT8WVN")
        assertThat(RecoverySecrets.normalizeRecoveryCode("  7K9F-2XQP-4MRT-8WVN  "))
            .isEqualTo(RecoverySecrets.normalizeRecoveryCode("7k9f2xqp4mrt8wvn"))
    }

    @Test
    fun `generated code is four dash-separated groups of four unambiguous chars`() {
        val code = RecoverySecrets.generateRecoveryCode(SecureRandom())
        assertThat(code).matches("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}")
        // Excludes the visually ambiguous 0/O/1/I/L.
        assertThat(code.filter { it in "01OIL" }).isEmpty()
    }

    @Test
    fun `generated codes are not constant`() {
        val random = SecureRandom()
        val codes = (0 until 20).map { RecoverySecrets.generateRecoveryCode(random) }.toSet()
        assertThat(codes.size).isGreaterThan(1)
    }
}
