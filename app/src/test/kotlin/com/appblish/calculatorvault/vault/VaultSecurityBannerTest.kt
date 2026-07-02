package com.appblish.calculatorvault.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the "Your device is at risk" banner gating (APP-207 — xlock parity): the banner is
 * shown *only* when All Files Access — the sole mandatory vault permission — is missing, and
 * is fully hidden once it is granted. No other permission gates it.
 */
class VaultSecurityBannerTest {
    @Test
    fun `no banner when All Files Access granted`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = true),
            )
        assertThat(warning).isNull()
    }

    @Test
    fun `banner warns about All Files Access when it is missing`() {
        val warning =
            VaultSecurityBanner.firstMissing(
                VaultSecurityBanner.State(hasAllFilesAccess = false),
            )
        assertThat(warning).isNotNull()
        assertThat(warning!!.permission).isEqualTo(VaultSecurityBanner.Permission.ALL_FILES_ACCESS)
    }
}
