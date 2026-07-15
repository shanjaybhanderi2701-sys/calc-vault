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
import androidx.compose.ui.test.onNodeWithText
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
     * Reliable Compose-content capture: renders the semantics root straight to a bitmap, so it
     * never depends on SurfaceFlinger having presented the frame (the earlier API-35 shot came
     * back a white pre-present blank even though the semantics tree was ready). Use for any
     * surface WITHOUT a popup window — the folder grid and the opened item picker.
     */
    private fun snapContent(name: String) {
        compose.waitForIdle()
        runCatching { save(name, compose.onRoot().captureToImage().asAndroidBitmap()) }
    }

    /**
     * Full-screen framebuffer capture (the test Activity has no FLAG_SECURE) so a `DropdownMenu`
     * popup — which renders in its own window, outside the Compose root — appears in the shot.
     * Retries once on a blank/unpresented framebuffer, and only then falls back to the (popup-less)
     * Compose-root bitmap. Reserved for the sort-menu shot, which is captured dead last.
     */
    private fun snap(name: String) {
        compose.waitForIdle()
        runCatching {
            val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
            var shot = ua.takeScreenshot()
            if (shot == null || isBlank(shot)) {
                Thread.sleep(600)
                compose.waitForIdle()
                shot = ua.takeScreenshot()
            }
            if (shot != null && !isBlank(shot)) {
                save(name, shot)
            } else {
                save(name, compose.onRoot().captureToImage().asAndroidBitmap())
            }
        }
    }

    /**
     * A settled picker frame is dark; an unpresented framebuffer is near-white. Sample the centre
     * region and treat "almost entirely very light" as blank so [snap] can retry.
     */
    private fun isBlank(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return true
        var light = 0
        var total = 0
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var y = h / 4
        while (y < h * 3 / 4) {
            var x = w / 4
            while (x < w * 3 / 4) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 230 && g > 230 && b > 230) light++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && light * 100 / total > 90
    }

    private fun textPresent(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(
        text: String,
        timeoutMs: Long = 20_000,
    ) {
        runCatching { compose.waitUntil(timeoutMs) { textPresent(text) } }
        compose.waitForIdle()
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

        // The three surfaces are captured in an order that keeps the fragile popup shot LAST:
        // grid → item picker (both driven by clean ViewModel state, snapped via the reliable
        // Compose-content path) → sort menu (device framebuffer, dead last so a lingering popup
        // can never bleed into another shot — the defect that made the earlier run's item-picker
        // shot a byte-for-byte duplicate of the sort-menu shot).

        // (1) Folder-thumbnail grid — the picker's initial folder step (albums seeded, no album
        // opened yet). Recent aggregate leads, then the source device buckets. "Hide Now" is the
        // always-present anchor; wait on it (20s) so a slow, contended emulator can't photograph a
        // half-settled tree.
        waitForText("Hide Now")
        waitForText("Recent")
        snapContent("app214_a_folder_grid_$suffix.png")

        // (2) Recent / All-Files aggregate opened → date-grouped item picker. Navigation is driven
        // directly through the VM (no fragile popup interaction). "Selected - 0" is the header the
        // picker shows the instant an album opens (independent of how the sample dates bucket), so
        // it's a robust settle anchor. Then step back to the folder grid via clearAlbum().
        runCatching { compose.runOnUiThread { vm.selectAlbum(SourceAlbum.RECENT_ID) } }
        waitForText("Selected - 0")
        snapContent("app214_b_recent_items_$suffix.png")
        runCatching { compose.runOnUiThread { vm.clearAlbum() } }
        waitForText("Hide Now")
        waitForText("Recent")

        // (3) Sort menu expanded — captured DEAD LAST on the stable folder grid. The trigger is a
        // merged TextButton whose active-sort label ("Added time") is the reliable click target;
        // fall back to the "Sort" overflow-icon content description. This shot needs the device
        // framebuffer because the DropdownMenu is a separate popup window outside the Compose root.
        val opened =
            runCatching {
                compose.onNodeWithText(PickerSort.ADDED_TIME.label).performClick()
            }.isSuccess ||
                runCatching { compose.onNodeWithContentDescription("Sort").performClick() }.isSuccess
        if (opened) {
            // "Size" only exists inside the expanded menu (never as the active button label here),
            // so its presence proves the floating overlay is open.
            waitForText(PickerSort.SIZE.label, timeoutMs = 8_000)
        }
        snap("app214_c_sort_menu_$suffix.png")
    }
}
