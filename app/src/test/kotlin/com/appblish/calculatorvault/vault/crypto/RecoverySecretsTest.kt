package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.SecureRandom

/**
 * Off-device proof of the recovery-secret transforms (APP-322 §2.1–2.2). The user re-types
 * the answer/code months later, so normalization must forgive casing and spacing while
 * staying byte-stable — otherwise the derived KEK changes and the vault locks out — and the
 * generated code must be high-entropy and copy-safe.
 */
class RecoverySecretsTest {
    @Test
    fun `answer normalization forgives casing and surrounding and internal whitespace`() {
        val canonical = RecoverySecrets.normalizeAnswer("Fluffy")

        assertThat(RecoverySecrets.normalizeAnswer("  fluffy ")).isEqualTo(canonical)
        assertThat(RecoverySecrets.normalizeAnswer("FLUFFY")).isEqualTo(canonical)
        assertThat(RecoverySecrets.normalizeAnswer("Fluffy")).isEqualTo("fluffy")
    }

    @Test
    fun `answer normalization collapses internal whitespace runs`() {
        assertThat(RecoverySecrets.normalizeAnswer("New   York")).isEqualTo("new york")
        assertThat(RecoverySecrets.normalizeAnswer("New\tYork")).isEqualTo("new york")
    }

    @Test
    fun `code normalization strips dashes and spaces and uppercases`() {
        assertThat(RecoverySecrets.normalizeCode("7k9f-2xqp")).isEqualTo("7K9F2XQP")
        assertThat(RecoverySecrets.normalizeCode("7K9F 2XQP")).isEqualTo("7K9F2XQP")
    }

    @Test
    fun `generated code is grouped and drawn from the unambiguous alphabet`() {
        val code = RecoverySecrets.generateCode(SecureRandom())

        // Four dash-separated groups of four (e.g. 7K9F-2XQP-4MRT-8WVN).
        assertThat(code).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")
        // No confusable glyphs.
        assertThat(code.filter { it in "01ILO" }).isEmpty()
    }

    @Test
    fun `generated codes are effectively unique`() {
        val random = SecureRandom()
        val codes = (1..500).map { RecoverySecrets.generateCode(random) }.toSet()
        // 500 draws from a ~78-bit space must not collide.
        assertThat(codes).hasSize(500)
    }
}
