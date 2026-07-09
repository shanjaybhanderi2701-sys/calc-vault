package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Off-device proof of the **3-wrap recovery envelope** (PIN Recovery spec §1, APP-322). One
 * immutable master DEK is wrapped independently by the PIN (A), the security answer (B), and
 * the recovery code (C); ANY one secret unwraps the SAME DEK; recovery is unwrap + re-wrap and
 * never re-encrypts the blobs; and no secret ever lands in plaintext. Each test stands a fresh
 * [VaultKeyFile] over the same file to model the reinstalled / relaunched app with no in-memory
 * state — the survive-uninstall guarantee the whole feature rests on.
 */
class ThreeWrapEnvelopeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun keyFile(): File = File(tmp.newFolder(".CalcVault"), ".vaultkey")

    private val pin = "1234"
    private val answer = "Fluffy the Cat"
    private val code = "7K9F-2XQP-4MRT-8WVN"

    /** Set up a vault with the PIN, then configure recovery (adds Wrap B + Wrap C). */
    private fun setUpVaultWithRecovery(file: File) {
        VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)
    }

    @Test
    fun `all three secrets unwrap the same DEK`() {
        val file = keyFile()
        val dekViaPin = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        // Each path, on a brand-new instance, yields byte-identical key material.
        val fromPin = VaultKeyFile(file).unlock(pin)
        val fromAnswer = VaultKeyFile(file).unlockWithAnswer(answer)
        val fromCode = VaultKeyFile(file).unlockWithRecoveryCode(code)

        assertThat(fromPin.encoded).isEqualTo(dekViaPin.encoded)
        assertThat(fromAnswer.encoded).isEqualTo(dekViaPin.encoded)
        assertThat(fromCode.encoded).isEqualTo(dekViaPin.encoded)
    }

    @Test
    fun `data encrypted with the DEK decrypts through every recovery path`() {
        val file = keyFile()
        val secret = "APP-322 :: one DEK, three doors".toByteArray()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        for (recovered in listOf(
            VaultKeyFile(file).unlock(pin),
            VaultKeyFile(file).unlockWithAnswer(answer),
            VaultKeyFile(file).unlockWithRecoveryCode(code),
        )) {
            val out = ByteArrayOutputStream()
            VaultCrypto(recovered).decrypt(ByteArrayInputStream(blob), out)
            assertThat(out.toByteArray()).isEqualTo(secret)
        }
    }

    @Test
    fun `security answer is case- and whitespace-insensitive`() {
        val file = keyFile()
        setUpVaultWithRecovery(file)
        val dek = VaultKeyFile(file).unlock(pin)

        // Different casing / spacing than setup, but normalizes to the same secret.
        val fromMessyAnswer = VaultKeyFile(file).unlockWithAnswer("  fluffy   THE cat ")
        assertThat(fromMessyAnswer.encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `recovery code unwraps regardless of dashes or case`() {
        val file = keyFile()
        setUpVaultWithRecovery(file)
        val dek = VaultKeyFile(file).unlock(pin)

        val noDashesLower = VaultKeyFile(file).unlockWithRecoveryCode("7k9f2xqp4mrt8wvn")
        assertThat(noDashesLower.encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `wrong secret on each path fails the tag`() {
        val file = keyFile()
        setUpVaultWithRecovery(file)

        assertFailsWrongPassphrase { VaultKeyFile(file).unlock("0000") }
        assertFailsWrongPassphrase { VaultKeyFile(file).unlockWithAnswer("wrong answer") }
        assertFailsWrongPassphrase { VaultKeyFile(file).unlockWithRecoveryCode("0000-0000-0000-0000") }
    }

    @Test
    fun `recovery paths are unavailable until recovery is set up`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate(pin) // PIN only, no recovery yet

        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isFalse()
        try {
            VaultKeyFile(file).unlockWithAnswer(answer)
            throw AssertionError("Expected NoSuchWrapException before recovery setup")
        } catch (expected: VaultKeyFile.NoSuchWrapException) {
            // Correct: no Wrap B exists yet.
        }
    }

    @Test
    fun `setUpRecovery marks recovery configured`() {
        val file = keyFile()
        VaultKeyFile(file).unlockOrCreate(pin)
        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isFalse()

        VaultKeyFile(file).setUpRecovery(pin, answer, code)
        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isTrue()
    }

    @Test
    fun `recovery reset re-wraps the PIN without touching the DEK or recovery wraps`() {
        val file = keyFile()
        val secret = "hidden before the PIN was reset".toByteArray()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)
        val blob =
            ByteArrayOutputStream()
                .also { out -> VaultCrypto(dek).encrypt(ByteArrayInputStream(secret), out) }
                .toByteArray()

        // Recovery flow: user lost the PIN → unwrap via the code → set a NEW pin (re-create Wrap A).
        val recoveredDek = VaultKeyFile(file).unlockWithRecoveryCode(code)
        VaultKeyFile(file).replacePinWrap(recoveredDek, "5678")

        // The new PIN opens it; the old PIN is dead; the recovery paths still work; the blob reads.
        val viaNewPin = VaultKeyFile(file).unlock("5678")
        assertThat(viaNewPin.encoded).isEqualTo(dek.encoded)
        assertFailsWrongPassphrase { VaultKeyFile(file).unlock(pin) }
        assertThat(VaultKeyFile(file).unlockWithAnswer(answer).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).unlockWithRecoveryCode(code).encoded).isEqualTo(dek.encoded)

        val out = ByteArrayOutputStream()
        VaultCrypto(VaultKeyFile(file).unlock("5678")).decrypt(ByteArrayInputStream(blob), out)
        assertThat(out.toByteArray()).isEqualTo(secret)
    }

    @Test
    fun `changing the PIN preserves both recovery wraps`() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        VaultKeyFile(file).rewrap(pin, "9999")

        assertThat(VaultKeyFile(file).isRecoveryConfigured()).isTrue()
        assertThat(VaultKeyFile(file).unlock("9999").encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).unlockWithAnswer(answer).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).unlockWithRecoveryCode(code).encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `regenerating a recovery secret retires the old one and keeps the others`() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        val newCode = "AAAA-BBBB-CCCC-DDDD"
        VaultKeyFile(file).replaceRecoveryCodeWrap(dek, newCode)

        assertThat(VaultKeyFile(file).unlockWithRecoveryCode(newCode).encoded).isEqualTo(dek.encoded)
        assertFailsWrongPassphrase { VaultKeyFile(file).unlockWithRecoveryCode(code) }
        // PIN and security answer are untouched.
        assertThat(VaultKeyFile(file).unlock(pin).encoded).isEqualTo(dek.encoded)
        assertThat(VaultKeyFile(file).unlockWithAnswer(answer).encoded).isEqualTo(dek.encoded)
    }

    @Test
    fun `no plaintext secret ever lands in the key file`() {
        val file = keyFile()
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        VaultKeyFile(file).setUpRecovery(pin, answer, code)

        val onDisk = file.readText()
        // No raw DEK, no answer, no code (nor its normalized form) appears in the file text. The
        // answer/code carry non-hex letters, so a hex-payload coincidence can never match them.
        val rawDekHex = dek.encoded.joinToString("") { "%02x".format(it) }
        assertThat(onDisk).doesNotContain(rawDekHex)
        assertThat(onDisk.lowercase()).doesNotContain("fluffy")
        assertThat(onDisk.uppercase()).doesNotContain("7K9F")
        assertThat(onDisk.uppercase()).doesNotContain(RecoverySecrets.normalizeRecoveryCode(code))

        // The all-numeric PIN would false-match ~1/65k of the time as a random hex substring, so
        // assert against the DECODED salt/iv/ciphertext bytes: the PIN's raw bytes (and the DEK's)
        // must not appear in any stored payload — the true "no recoverable plaintext" guarantee.
        val payload = String(decodedPayloadBytes(onDisk), Charsets.ISO_8859_1)
        assertThat(payload).doesNotContain(pin)
        assertThat(payload).doesNotContain(String(dek.encoded, Charsets.ISO_8859_1))
    }

    /** Concatenated raw bytes of every wrap's salt/iv/ciphertext (the hex fields after tag:iter). */
    private fun decodedPayloadBytes(fileText: String): ByteArray {
        val hex =
            fileText
                .trim()
                .lines()
                .drop(1) // skip the CVKEY2 magic line
                .flatMap { it.split(":").drop(2) } // drop tag + iterations, keep salt/iv/wrapped
                .joinToString("")
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    @Test
    fun `each wrap uses an independent salt and iv`() {
        val file = keyFile()
        setUpVaultWithRecovery(file)

        val lines = file.readText().trim().lines()
        assertThat(lines[0]).isEqualTo("CVKEY2")
        val slotFields = lines.drop(1).map { it.split(":") }
        val salts = slotFields.map { it[2] }
        val ivs = slotFields.map { it[3] }
        assertThat(salts.toSet()).hasSize(3)
        assertThat(ivs.toSet()).hasSize(3)
    }

    @Test
    fun `legacy v1 single-wrap key file still opens with the PIN`() {
        val file = keyFile()
        // Hand-write a v1 envelope the way the pre-recovery build did, by wrapping a known DEK.
        // Easiest faithful path: create via the current code, then downgrade the on-disk format
        // to v1 by re-serializing slot A as a legacy single line.
        val dek = VaultKeyFile(file).unlockOrCreate(pin)
        val v2 = file.readText().trim().lines()
        val slotA = v2.first { it.startsWith("A:") }.split(":")
        // v1 format: version:iterations:salt:iv:wrapped
        file.writeText("1:${slotA[1]}:${slotA[2]}:${slotA[3]}:${slotA[4]}")

        val reopened = VaultKeyFile(file).unlock(pin)
        assertThat(reopened.encoded).isEqualTo(dek.encoded)
    }

    private fun assertFailsWrongPassphrase(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected WrongPassphraseException")
        } catch (expected: VaultKeyFile.WrongPassphraseException) {
            // Correct.
        }
    }
}
