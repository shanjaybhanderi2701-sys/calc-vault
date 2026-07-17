package com.appblish.calculatorvault.navigation

import android.content.pm.ActivityInfo
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the whole-app orientation policy (APP-420): only the full-screen media viewers —
 * the gallery pager (photo viewer + video player) and the folder slideshow — may rotate;
 * every other surface (disguise, auth spine, vault home, grids, settings, recycle bin,
 * recovery, dialogs) and an unresolved null route are pinned to portrait. This is the pure
 * half of the feature, unit-tested in isolation from Compose/lifecycle (mirrors
 * [SessionLockTest]); [VaultNavHost] applies the result to `Activity.requestedOrientation`.
 */
class OrientationPolicyTest {
    @Test
    fun `the gallery viewer may rotate`() {
        // Both the route pattern and a concrete instance (with args + folder query) resolve.
        assertThat(OrientationPolicy.allowsRotation(VaultDestinations.VIEWER)).isTrue()
        val instance = VaultDestinations.viewer("abc", VaultCategory.VIDEOS, folderId = "f1")
        assertThat(OrientationPolicy.allowsRotation(instance)).isTrue()
        assertThat(OrientationPolicy.forRoute(instance))
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
    }

    @Test
    fun `the folder slideshow may rotate`() {
        assertThat(OrientationPolicy.allowsRotation(VaultDestinations.SLIDESHOW)).isTrue()
        val instance = VaultDestinations.slideshow(VaultCategory.PHOTOS)
        assertThat(OrientationPolicy.forRoute(instance))
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
    }

    @Test
    fun `every non-viewer surface is portrait-locked`() {
        listOf(
            VaultDestinations.GATE,
            VaultDestinations.ONBOARDING,
            VaultDestinations.CALCULATOR,
            VaultDestinations.VAULT_HOME,
            VaultDestinations.SEARCH,
            VaultDestinations.CATEGORY,
            VaultDestinations.HIDE,
            VaultDestinations.RECYCLE_BIN,
            VaultDestinations.RECOVERY_ENTRY,
            VaultDestinations.RECOVERY_SETUP,
            VaultDestinations.RECOVERY_UNLOCK,
            VaultDestinations.SETTINGS,
            VaultDestinations.SETTINGS_CHANGE_PIN,
            VaultDestinations.SETTINGS_PERMISSIONS,
            VaultDestinations.SETTINGS_LANGUAGE,
            VaultDestinations.SETTINGS_PIN_RECOVERY,
        ).forEach { route ->
            assertThat(OrientationPolicy.allowsRotation(route)).isFalse()
            assertThat(OrientationPolicy.forRoute(route))
                .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }
    }

    @Test
    fun `a null route is portrait-locked, never rotatable`() {
        assertThat(OrientationPolicy.allowsRotation(null)).isFalse()
        assertThat(OrientationPolicy.forRoute(null)).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test
    fun `a category route is not mistaken for the viewer by prefix`() {
        // "category/…" and "recyclebin" must not accidentally match the "viewer"/"slideshow"
        // bases — the check is on the whole base segment, not a substring.
        assertThat(OrientationPolicy.allowsRotation(VaultDestinations.category(VaultCategory.VIDEOS))).isFalse()
        assertThat(OrientationPolicy.allowsRotation(VaultDestinations.RECYCLE_BIN)).isFalse()
    }
}
