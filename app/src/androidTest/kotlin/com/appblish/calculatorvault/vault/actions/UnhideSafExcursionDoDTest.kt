package com.appblish.calculatorvault.vault.actions

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblish.calculatorvault.navigation.SessionLock
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.vault.model.VaultCategory
import com.appblish.calculatorvault.vault.model.VaultItem
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APP-301 regression: unhide → **"Choose a folder…"** must arm the one-shot re-lock
 * suppression *before* launching the SAF tree picker, so returning from the opaque
 * DocumentsUI activity resumes the unhide instead of relocking the vault and silently
 * dropping the operation.
 *
 * The defect was a UI-wiring gap the JVM `WriteStrategyOrderTest`/`SessionLockTest` can't
 * catch: the SAF-first write order was correct, and the suppression primitive worked, but
 * the picker call site never armed it — so the DocumentsUI `ON_STOP` fired [SessionLock.relock]
 * and tore the pending unhide down. This drives the *real* production [PhotoActionsHost]
 * unhide dialog and asserts the wiring, using a fake [ActivityResultRegistry] so the tap
 * never opens the real (flaky, external) DocumentsUI. On the pre-fix code the picker
 * launches but the excursion is never armed, so the suppression assertion fails.
 */
@RunWith(AndroidJUnit4::class)
class UnhideSafExcursionDoDTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // Clear any suppression an earlier test/interaction may have left armed.
        SessionLock.consumeGrantRoundTrip()
    }

    @After
    fun cleanUp() {
        SessionLock.consumeGrantRoundTrip()
    }

    @Test
    fun choosingAFolderArmsTheRelockSuppressionBeforeLaunchingThePicker() {
        val launched = AtomicBoolean(false)
        // A fake registry: record the launch, never dispatch a result and never start the
        // real DocumentsUI. This isolates the wiring under test from the SAF picker itself.
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    launched.set(true)
                }
            }
        val registryOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }

        val controller = PhotoActionsController().apply { open(PhotoAction.UNHIDE) }
        val item =
            VaultItem(
                id = "item-1",
                category = VaultCategory.PHOTOS,
                originalName = "X1.jpg",
                dateLabel = "Today",
                sortKey = 0L,
                relativePath = "Pictures/FolderX/",
            )

        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                CalculatorVaultTheme {
                    PhotoActionsHost(
                        controller = controller,
                        item = item,
                        albumName = "FolderX",
                        albums = emptyList(),
                        callbacks =
                            PhotoActionCallbacks(
                                onMove = {},
                                onCreateFolder = {},
                                onUnhide = {},
                                onMoveToBin = {},
                                onPermanentDelete = {},
                            ),
                    )
                }
            }
        }

        // Pre-condition: nothing armed before the user touches the picker.
        assertThat(SessionLock.consumeGrantRoundTrip()).isFalse()

        compose.onNodeWithText("Choose a folder", substring = true).performClick()
        compose.waitForIdle()

        // The picker fired…
        assertThat(launched.get()).isTrue()
        // …AND the SAF excursion armed the one-shot suppression, so the DocumentsUI ON_STOP
        // that follows is skipped instead of relocking the vault (the APP-301 fix).
        assertThat(SessionLock.consumeGrantRoundTrip()).isTrue()
    }
}
