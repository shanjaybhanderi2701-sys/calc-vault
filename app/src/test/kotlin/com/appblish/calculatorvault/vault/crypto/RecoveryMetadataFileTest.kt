package com.appblish.calculatorvault.vault.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM round-trip proof of the survive-uninstall recovery-metadata file (APP-338): the
 * non-secret security-question prompt persists and reads back for presets, custom prompts with
 * awkward characters, and a re-write, while an absent / malformed / blank file degrades to
 * `null` rather than crashing a recovery screen.
 */
class RecoveryMetadataFileTest {
    @get:Rule val temp = TemporaryFolder()

    private fun metaFile(name: String = ".recoverymeta") = RecoveryMetadataFile(File(temp.root, name))

    @Test
    fun `absent file reads back as null`() {
        assertThat(metaFile().exists()).isFalse()
        assertThat(metaFile().readQuestion()).isNull()
    }

    @Test
    fun `a preset prompt round-trips`() {
        val meta = metaFile()
        meta.writeQuestion("What was your first pet's name?")
        assertThat(meta.exists()).isTrue()
        assertThat(metaFile().readQuestion()).isEqualTo("What was your first pet's name?")
    }

    @Test
    fun `a custom prompt with colons and unicode round-trips untouched`() {
        val custom = "Street : city — where I grew up? 🏠 名前"
        metaFile().writeQuestion(custom)
        assertThat(metaFile().readQuestion()).isEqualTo(custom)
    }

    @Test
    fun `a prompt containing a newline is preserved`() {
        val multiline = "Line one\nLine two"
        metaFile().writeQuestion(multiline)
        assertThat(metaFile().readQuestion()).isEqualTo(multiline)
    }

    @Test
    fun `re-writing replaces the previous prompt`() {
        val meta = metaFile()
        meta.writeQuestion("In which city were you born?")
        meta.writeQuestion("What is your mother's maiden name?")
        assertThat(metaFile().readQuestion()).isEqualTo("What is your mother's maiden name?")
    }

    @Test
    fun `a file without the magic header reads back as null`() {
        File(temp.root, ".recoverymeta").writeText("not our format\nWhat is your pet?")
        assertThat(metaFile().readQuestion()).isNull()
    }

    @Test
    fun `a magic header with a blank body reads back as null`() {
        File(temp.root, ".recoverymeta").writeText("CVRMETA1\n   ")
        assertThat(metaFile().readQuestion()).isNull()
    }

    @Test
    fun `a bare magic header with no newline reads back as null`() {
        File(temp.root, ".recoverymeta").writeText("CVRMETA1")
        assertThat(metaFile().readQuestion()).isNull()
    }

    @Test
    fun `the on-disk file carries no secret material — only the plaintext prompt and magic`() {
        // §1 boundary check: the metadata file must never hold a hash/salt/IV/key — just the prompt.
        metaFile().writeQuestion("What is your mobile No.?")
        val onDisk = File(temp.root, ".recoverymeta").readText()
        assertThat(onDisk).isEqualTo("CVRMETA1\nWhat is your mobile No.?")
    }
}
