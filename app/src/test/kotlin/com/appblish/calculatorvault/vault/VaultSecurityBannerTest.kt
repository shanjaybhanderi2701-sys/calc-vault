package com.appblish.calculatorvault.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the "Your device is at risk" banner gating (APP-207 — xlock parity): the banner is
 * shown *only* when a required permission is actually missing, names the specific missing
 * permission, and is fully hidden once the applicable required set is granted.
 */
class VaultSecurityBannerTest {
    @Test
    fun `no banner when All Files Access granted and camera not applicable`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = true, hasCamera = null),
            )
        assertThat(warning).isNull()
    }

    @Test
    fun `no banner when All Files Access granted and camera granted`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = true, hasCamera = true),
            )
        assertThat(warning).isNull()
    }

    @Test
    fun `banner warns about All Files Access when it is missing`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = false, hasCamera = null),
            )
        assertThat(warning).isNotNull()
        assertThat(warning!!.permission).isEqualTo(VaultSecurityBanner.Permission.ALL_FILES_ACCESS)
    }

    @Test
    fun `All Files Access takes priority over a missing camera`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = false, hasCamera = false),
            )
        assertThat(warning!!.permission).isEqualTo(VaultSecurityBanner.Permission.ALL_FILES_ACCESS)
    }

    @Test
    fun `banner warns about camera only once it is applicable and missing`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = true, hasCamera = false),
            )
        assertThat(warning!!.permission).isEqualTo(VaultSecurityBanner.Permission.CAMERA)
    }

    @Test
    fun `a missing camera is ignored while intruder selfie is off`() {
        // hasCamera == null models "not applicable" (Intruder Selfie disabled).
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = true, hasCamera = null),
            )
        assertThat(warning).isNull()
    }
}
