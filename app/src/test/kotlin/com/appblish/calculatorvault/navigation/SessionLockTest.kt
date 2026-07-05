package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.vault.VaultSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the vault re-lock policy (APP-205, re-scoped for Phase 1 by APP-225): every route
 * behind the unlocked vault re-locks on background, while the disguise / auth spine does
 * not; the primer's grant round-trip arms a strictly one-shot suppression; and
 * [SessionLock.relock] forgets the in-memory session so a backgrounded vault cannot resume.
 */
class SessionLockTest {
    @Test
    fun `pre-vault surfaces do not re-lock on background`() {
        listOf(
            VaultDestinations.GATE,
            VaultDestinations.ONBOARDING,
            VaultDestinations.CALCULATOR,
        ).forEach { route ->
            assertThat(SessionLock.isVaultSurface(route)).isFalse()
        }
    }

    @Test
    fun `a null route is treated as pre-vault and does not re-lock`() {
        assertThat(SessionLock.isVaultSurface(null)).isFalse()
    }

    @Test
    fun `vault surfaces re-lock on background`() {
        listOf(
            VaultDestinations.VAULT_HOME,
            VaultDestinations.RECYCLE_BIN,
            VaultDestinations.SEARCH,
            VaultDestinations.SETTINGS,
            VaultDestinations.SETTINGS_CHANGE_PIN,
            VaultDestinations.category(com.appblish.calculatorvault.vault.model.VaultCategory.PHOTOS),
            VaultDestinations.viewer("item-1"),
        ).forEach { route ->
            assertThat(SessionLock.isVaultSurface(route)).isTrue()
        }
    }

    @Test
    fun `grant round-trip suppression is one-shot`() {
        // Not armed: nothing suppressed.
        assertThat(SessionLock.consumeGrantRoundTrip()).isFalse()

        // Armed: exactly the next consume is suppressed, then the flag is spent.
        SessionLock.beginGrantRoundTrip()
        assertThat(SessionLock.consumeGrantRoundTrip()).isTrue()
        assertThat(SessionLock.consumeGrantRoundTrip()).isFalse()
    }

    @Test
    fun `relock forgets the session passphrase and namespace`() {
        VaultSession.begin(code = "1234", namespace = "decoy_1")
        assertThat(VaultSession.passphrase).isEqualTo("1234")

        SessionLock.relock()

        assertThat(VaultSession.passphrase).isNull()
        assertThat(VaultSession.namespace).isEmpty()
    }
}
