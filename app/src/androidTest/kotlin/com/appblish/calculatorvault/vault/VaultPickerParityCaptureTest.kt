package com.appblish.calculatorvault.vault

import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * APP-214 — on-device VISUAL capture of the xlock-parity in-vault picker. Renders the **real
 * production [HideImportScreen]** on the CI emulator (API 30/35) and writes full-screen PNGs of
 * the three acceptance surfaces to `/sdcard/app214-evidence/` so the CI job can `adb pull` +
 * upload them as artifacts, then a human/agent pairs them side-by-side with the xlock
 * "Photo Hide Flow" reference (APP-142 attachment · panels 4/7 + sort menu).
 *
 * Why an instrumented capture and not a manual `adb screencap`: the live vault Activity sets
 * `FLAG_SECURE` (renders `screencap` black) and the shared local emulator ANRs under host load.
 * This test hosts the picker in a plain (non-secure) test Activity, drives it through its own
 * sample-data path ([HideImportViewModel] with `mediaSource = null` → seeded Recent / Camera /
 * Screenshots / Download albums, matching the reference), and photographs the settled frames —
 * deterministic and host-independent.
 *
 * Shots (suffixed with the emulator API level):
 *   1. `app214_a_folder_grid_api<lvl>.png` — 2-col folder-thumbnail grid, "Recent" first
 *      (xlock ref panel 4, "Folders / All").
 *   2. `app214_b_recent_items_api<lvl>.png` — the Recent / All-Files aggregate opened, showing
 *      the date-grouped item picker (xlock ref panel 7, "Recent / 100 items").
 *   3. `app214_c_sort_menu_api<lvl>.png` — the sort menu expanded (Added time / Last modified /
 *      Name / Size), matching the reference's floating sort overlay.
 *
 * Capture is deliberately **best-effort**: every stage is wrapped so a capture hiccup on a
 * given emulator never turns the instrumented job red (that would strand other DoD gates).
 */
@RunWith(AndroidJUnit4::class)
class VaultPickerParityCaptureTest {
    @get:Rule
    val compose = createComposeRule()

    private val suffix: String = "api${Build.VERSION.SDK_INT}"

    private fun outDir(): File {
        // MANAGE_EXTERNAL_STORAGE lets us write to the shared volume root, which `adb pull`
        // can read on API 30+ (app-private external dirs are hidden from the shell user).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { DoDTestSupport.grantAllFilesAccess(ctx) }
        return File(Environment.getExternalStorageDirectory(), "app214-evidence").also { it.mkdirs() }
    }

    private fun save(
        name: String,
        bitmap: Bitmap,
    ) {
        runCatching {
            File(outDir(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * Full-screen framebuffer capture (the test Activity has no FLAG_SECURE) so DropdownMenu
     * popups render in the shot; falls back to the Compose root bitmap if the platform screenshot
     * comes back null.
     */
    private fun snap(name: String) {
        compose.waitForIdle()
        runCatching {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (shot != null) {
                save(name, shot)
            } else {
                save(name, compose.onRoot().captureToImage().asAndroidBitmap())
            }
        }
    }

    @Test
    fun capturePickerParitySurfaces() {
        // Pre-grant the media read permission so the picker's runtime-permission LaunchedEffect
        // resolves silently instead of popping a system dialog over the shot.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaPerm =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "android.permission.READ_MEDIA_IMAGES"
            } else {
                "android.permission.READ_EXTERNAL_STORAGE"
            }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching { automation.grantRuntimePermission(ctx.packageName, mediaPerm) }

        // mediaSource = null → the VM serves deterministic sample albums/items (Recent, Camera,
        // Screenshots, Download) that mirror the xlock reference. Repository defaults to the
        // in-memory fallback (VaultGraph, uninitialised in tests), which the picker never touches
        // until Hide Now, so no device key/graph is required.
        val vm = HideImportViewModel(VaultCategory.PHOTOS, mediaSource = null)

        compose.setContent {
            CalculatorVaultTheme {
                Surface {
                    HideImportScreen(viewModel = vm, onBack = {}, onHidden = {})
                }
            }
        }

        // (1) Folder-thumbnail grid — the picker's initial folder step (albums seeded, no album
        // opened yet). Recent aggregate leads, then the source device buckets.
        runCatching {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Recent").fetchSemanticsNodes().isNotEmpty()
            }
        }
        snap("app214_a_folder_grid_$suffix.png")

        // (2) Recent / All-Files aggregate opened → date-grouped item picker ("Today" section).
        runCatching { compose.runOnUiThread { vm.selectAlbum(SourceAlbum.RECENT_ID) } }
        runCatching {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty()
            }
        }
        snap("app214_b_recent_items_$suffix.png")

        // (3) Sort menu expanded — tap the "Sort" control (merged TextButton) and wait for a
        // menu-only option to appear before photographing the floating overlay.
        runCatching { compose.onNodeWithContentDescription("Sort").performClick() }
        runCatching {
            compose.waitUntil(5_000) {
                // "Last modified" only exists inside the expanded menu (the button label is
                // the active sort, "Added time"), so its presence proves the menu is open.
                compose.onAllNodesWithText("Last modified").fetchSemanticsNodes().isNotEmpty()
            }
        }
        snap("app214_c_sort_menu_$suffix.png")
    }
}
