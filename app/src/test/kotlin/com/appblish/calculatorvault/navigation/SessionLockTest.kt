package com.appblish.calculatorvault.navigation

import com.appblish.calculatorvault.vault.VaultSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the vault re-lock policy (APP-205, re-scoped for Phase 1 by APP-225): every route
 * behind the unlocked vault re-locks on background, while the disguise / auth spine does
 * not; the primer's grant round-trip arms a strictly one-shot suppression that auto-expires
 * after two minutes (APP-225 board finding P1b — a stale armed flag must never mask a real
 * backgrounding); and [SessionLock.relock] forgets the in-memory session so a backgrounded
 * or backed-out vault cannot resume.
 */
class SessionLockTest {
    /** Mirrors SessionLock.GRANT_ROUND_TRIP_EXPIRY_MS. */
    private val expiryMs = 2 * 60 * 1000L

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
            VaultDestinations.viewer("item-1", com.appblish.calculatorvault.vault.model.VaultCategory.PHOTOS),
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
    fun `grant round-trip suppresses while still inside the expiry window`() {
        SessionLock.beginGrantRoundTrip(nowMs = 1_000L)
        // A realistic Settings round-trip: seconds, well inside the window.
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = 1_000L + 15_000L)).isTrue()

        SessionLock.beginGrantRoundTrip(nowMs = 1_000L)
        // Just under the boundary still counts as the round-trip returning.
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = 1_000L + expiryMs - 1)).isTrue()
    }

    @Test
    fun `a stale armed grant round-trip expires and does not suppress a later re-lock`() {
        // Armed but never consumed (grant intent failed / user abandoned the trip): a
        // backgrounding at or after the expiry must re-lock as normal (finding P1b).
        SessionLock.beginGrantRoundTrip(nowMs = 1_000L)
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = 1_000L + expiryMs)).isFalse()

        // The expired flag is spent too — it cannot resurrect on a subsequent consume.
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = 1_000L + expiryMs)).isFalse()
    }

    @Test
    fun `re-arming after an expired round-trip works normally`() {
        SessionLock.beginGrantRoundTrip(nowMs = 0L)
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = expiryMs + 1)).isFalse()

        SessionLock.beginGrantRoundTrip(nowMs = expiryMs + 2)
        assertThat(SessionLock.consumeGrantRoundTrip(nowMs = expiryMs + 3)).isTrue()
    }

    @Test
    fun `relock forgets the session passphrase and namespace`() {
        VaultSession.begin(code = "1234", namespace = "decoy_1")
        assertThat(VaultSession.passphrase).isEqualTo("1234")

        SessionLock.relock()

        assertThat(VaultSession.passphrase).isNull()
        assertThat(VaultSession.namespace).isEmpty()
    }

    @Test
    fun `lockNow drops the session and is safe when nothing is unlocked`() {
        // Back-out path (finding P1a): called directly from the vault-home BackHandler.
        VaultSession.begin(code = "1234", namespace = "decoy_2")
        SessionLock.lockNow()
        assertThat(VaultSession.passphrase).isNull()

        // Idempotent: calling again with no live session must not throw.
        SessionLock.lockNow()
        assertThat(VaultSession.passphrase).isNull()
        assertThat(VaultSession.namespace).isEmpty()
    }
}
