package com.appblish.calculatorvault.vault.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * APP-293 P0-2 — the SAF docId → (RELATIVE_PATH?, label) mapping behind "restore to a
 * different location". The old parse returned null for anything but a non-root primary
 * path, which silently reverted the dialog to "Original"; the contract now is: every
 * resolvable tree is accepted, and only the RELATIVE_PATH route is conditional.
 */
class ChosenFolderTest {
    @Test
    fun `primary volume path maps to relative path and folder label`() {
        val (rel, label) = parseChosenDocId("primary:Pictures/Vault")
        assertThat(rel).isEqualTo("Pictures/Vault/")
        assertThat(label).isEqualTo("Vault")
    }

    @Test
    fun `nested primary path labels with the leaf folder`() {
        val (rel, label) = parseChosenDocId("primary:DCIM/Camera/Trip")
        assertThat(rel).isEqualTo("DCIM/Camera/Trip/")
        assertThat(label).isEqualTo("Trip")
    }

    @Test
    fun `home volume maps into public Documents`() {
        val (rel, label) = parseChosenDocId("home:Restored")
        assertThat(rel).isEqualTo("Documents/Restored/")
        assertThat(label).isEqualTo("Restored")
    }

    @Test
    fun `sd card volume yields no relative path but keeps the folder label`() {
        val (rel, label) = parseChosenDocId("1A2B-3C4D:Photos/Family")
        assertThat(rel).isNull()
        assertThat(label).isEqualTo("Family")
    }

    @Test
    fun `primary root has no relative path and a storage label`() {
        val (rel, label) = parseChosenDocId("primary:")
        assertThat(rel).isNull()
        assertThat(label).isEqualTo("Internal storage")
    }

    @Test
    fun `sd card root labels generically`() {
        val (rel, label) = parseChosenDocId("1A2B-3C4D:")
        assertThat(rel).isNull()
        assertThat(label).isEqualTo("SD card")
    }
}
