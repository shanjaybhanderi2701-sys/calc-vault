package com.appblish.calculatorvault.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Exercises the shared [BaseCredentialStore] logic through the in-memory store. Because
 * all hashing/decoy/resolution lives in the base class, these results hold identically for
 * the on-device [EncryptedCredentialStore].
 */
class CredentialStoreTest {
    private fun store() = InMemoryCredentialStore()

    @Test
    fun `real pin resolves to the real vault`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `unknown code resolves to null so it is treated as arithmetic`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            assertThat(store.resolve("9999")).isNull()
        }

    @Test
    fun `decoy pin opens a decoy vault that is separate from the real vault`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            val slot = store.addDecoyPin("5555")
            assertThat(slot).isNotNull()

            val real = store.resolve("1234")
            val decoy = store.resolve("5555")

            assertThat(real).isEqualTo(VaultKind.Real)
            assertThat(decoy).isEqualTo(VaultKind.Decoy(slot!!))
            // The core Fake Password guarantee: the two spaces never share storage.
            assertThat(decoy!!.storageId).isNotEqualTo(real!!.storageId)
        }

    @Test
    fun `a decoy pin colliding with the real pin is rejected`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            assertThat(store.addDecoyPin("1234")).isNull()
            // The real PIN still opens the real vault, never a decoy.
            assertThat(store.resolve("1234")).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `two decoys get distinct slots and distinct storage`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            val a = store.addDecoyPin("5555")
            val b = store.addDecoyPin("6666")
            assertThat(a).isNotEqualTo(b)
            assertThat(store.decoySlots()).containsExactly(a, b)

            val storageA = VaultKind.Decoy(a!!).storageId
            val storageB = VaultKind.Decoy(b!!).storageId
            assertThat(storageA).isNotEqualTo(storageB)
            // ...and neither collides with the real vault.
            assertThat(setOf(storageA, storageB)).doesNotContain(VaultKind.Real.storageId)
        }

    @Test
    fun `a duplicate decoy pin is rejected`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            assertThat(store.addDecoyPin("5555")).isNotNull()
            assertThat(store.addDecoyPin("5555")).isNull()
        }

    @Test
    fun `removing a decoy makes its pin resolve to nothing`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            val slot = store.addDecoyPin("5555")!!
            store.removeDecoy(slot)
            assertThat(store.resolve("5555")).isNull()
            assertThat(store.decoySlots()).isEmpty()
        }

    @Test
    fun `recovery answer verifies ignoring case and surrounding space`() =
        runTest {
            val store = store()
            store.setRecovery(
                RecoverySetup(
                    question = SecurityQuestion.PET_NAME,
                    answer = "Rex",
                    recoveryEmail = "owner@example.com",
                    hint = "the dog",
                ),
            )
            assertThat(store.verifyRecoveryAnswer("  rex ")).isTrue()
            assertThat(store.verifyRecoveryAnswer("fido")).isFalse()
        }

    @Test
    fun `recovery info exposes question email and hint but not the answer`() =
        runTest {
            val store = store()
            store.setRecovery(
                RecoverySetup(
                    question = SecurityQuestion.BIRTH_CITY,
                    answer = "Ahmedabad",
                    recoveryEmail = "owner@example.com",
                    hint = "home town",
                ),
            )
            val info = store.recoveryInfo()!!
            assertThat(info.question).isEqualTo(SecurityQuestion.BIRTH_CITY)
            assertThat(info.recoveryEmail).isEqualTo("owner@example.com")
            assertThat(info.hint).isEqualTo("home town")
        }

    @Test
    fun `resetting the real pin changes what unlocks the vault`() =
        runTest {
            val store = store()
            store.setRealPin("1234")
            store.setRealPin("8765")
            assertThat(store.resolve("1234")).isNull()
            assertThat(store.resolve("8765")).isEqualTo(VaultKind.Real)
        }

    @Test
    fun `onboarding flag flips only after completion and clears on reset`() =
        runTest {
            val store = store()
            assertThat(store.isOnboarded()).isFalse()
            store.completeOnboarding()
            assertThat(store.isOnboarded()).isTrue()
            store.clearAll()
            assertThat(store.isOnboarded()).isFalse()
        }
}
