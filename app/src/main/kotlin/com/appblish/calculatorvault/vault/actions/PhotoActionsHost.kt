package com.appblish.calculatorvault.vault.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.appblish.calculatorvault.navigation.SessionLock
import com.appblish.calculatorvault.vault.model.UnhideDestination
import com.appblish.calculatorvault.vault.model.VaultItem

/** The four single-photo actions this host can present (design §5–§9). */
enum class PhotoAction { MOVE, UNHIDE, DELETE, PROPERTY }

/**
 * Imperative handle a screen holds to open one of the four action surfaces for the photo
 * it currently owns. Kept tiny and UI-thread only; the host reads [active] and renders the
 * matching dialog, then calls [close] when the surface finishes or is dismissed.
 */
class PhotoActionsController {
    var active by mutableStateOf<PhotoAction?>(null)
        private set

    fun open(action: PhotoAction) {
        active = action
    }

    fun close() {
        active = null
    }
}

@Composable
fun rememberPhotoActionsController(): PhotoActionsController = remember { PhotoActionsController() }

/**
 * The commands the host issues back to the owning screen/ViewModel. All are fire-and-forget
 * from the host's side (the ViewModel runs them off the main thread and reflects the result
 * through its own state / a message channel).
 */
data class PhotoActionCallbacks(
    val onMove: (folderId: String?) -> Unit,
    val onCreateFolder: (name: String) -> Unit,
    val onUnhide: (UnhideDestination) -> Unit,
    val onMoveToBin: () -> Unit,
    val onPermanentDelete: () -> Unit,
)

/**
 * Renders whichever single-photo action surface [controller] has open for [item], wiring the
 * design's §5–§9 dialogs to [callbacks]. Owns the transient dialog-local state: the delete
 * flow's 2-step progression, the unhide destination choice, and the SAF folder picker used
 * by "Choose a folder…". The host closes itself after a terminal action so the screen never
 * has to reset the controller by hand.
 */
@Composable
fun PhotoActionsHost(
    controller: PhotoActionsController,
    item: VaultItem,
    albumName: String?,
    albums: List<AlbumOption>,
    callbacks: PhotoActionCallbacks,
) {
    when (controller.active) {
        null -> Unit

        PhotoAction.MOVE ->
            MoveToSheet(
                itemCount = 1,
                albums = albums,
                currentFolderId = item.folderId,
                onDismiss = controller::close,
                onCreateFolder = callbacks.onCreateFolder,
                onMove = { folderId ->
                    callbacks.onMove(folderId)
                    controller.close()
                },
            )

        PhotoAction.UNHIDE -> UnhideHost(item, controller, callbacks)

        PhotoAction.DELETE -> DeleteHost(controller, callbacks)

        PhotoAction.PROPERTY ->
            PropertyDialog(
                title = "Details",
                rows = PhotoProperties.rows(item, albumName),
                onDismiss = controller::close,
            )
    }
}

@Composable
private fun UnhideHost(
    item: VaultItem,
    controller: PhotoActionsController,
    callbacks: PhotoActionCallbacks,
) {
    var choice by remember { mutableStateOf(UnhideChoice.ORIGINAL) }
    var chosenFolder by remember { mutableStateOf<ChosenFolder?>(null) }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val picked = uri?.let { chosenFolderFrom(context, it) }
            if (picked != null) {
                chosenFolder = picked
                choice = UnhideChoice.CHOSEN
            } else if (chosenFolder == null) {
                // Backed out with nothing ever picked — fall back to the safe default choice.
                choice = UnhideChoice.ORIGINAL
            }
        }
    UnhideDialog(
        itemCount = 1,
        originalPath = item.relativePath,
        choice = choice,
        chosenFolder = chosenFolder,
        onChoiceChange = { choice = it },
        onPickFolder = {
            // APP-301: the opaque DocumentsUI tree picker stops CalcVault; suppress the
            // one-shot re-lock so returning with the picked folder resumes the unhide.
            SessionLock.beginSafExcursion()
            picker.launch(null)
        },
        onConfirm = { destination ->
            callbacks.onUnhide(destination)
            controller.close()
        },
        onDismiss = controller::close,
    )
}

@Composable
private fun DeleteHost(
    controller: PhotoActionsController,
    callbacks: PhotoActionCallbacks,
) {
    var step by remember { mutableStateOf(DeleteStep.CHOICE) }
    DeleteDialog(
        itemCount = 1,
        step = step,
        onMoveToBin = {
            callbacks.onMoveToBin()
            controller.close()
        },
        onChoosePermanent = { step = DeleteStep.CONFIRM_PERMANENT },
        onConfirmPermanent = {
            callbacks.onPermanentDelete()
            controller.close()
        },
        onDismiss = controller::close,
    )
}

/**
 * The folder the user picked in the SAF tree picker, carrying everything the write path
 * needs (APP-293 P0-2): the MediaStore RELATIVE_PATH when the tree parses to a primary-
 * volume path (preferred, gallery-indexed route), the tree Uri itself as the direct SAF
 * write route for everything else, and the human label for the dialog + result copy.
 */
data class ChosenFolder(
    val relativePath: String?,
    val treeUri: String,
    val label: String,
) {
    fun toDestination(): UnhideDestination.Chosen = UnhideDestination.Chosen(relativePath, treeUri, label)
}

/**
 * Capture a SAF tree pick as a [ChosenFolder]. Unlike the earlier primary-volume-only
 * parse, every resolvable tree is accepted — a Samsung/MIUI-style non-primary volume just
 * skips the RELATIVE_PATH route and writes through the tree Uri — so a picked folder never
 * silently degrades back to "Original". Also takes the persistable grant (best-effort) so
 * a long bulk unhide keeps write access if the process is recycled mid-batch.
 */
fun chosenFolderFrom(
    context: Context,
    treeUri: Uri,
): ChosenFolder? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
    val (relativePath, label) = parseChosenDocId(docId)
    return ChosenFolder(relativePath, treeUri.toString(), label)
}

/**
 * Pure docId → (RELATIVE_PATH?, label) mapping, split out for JVM tests. `primary:` paths
 * map directly; AOSP's `home:` volume is the public Documents dir; anything else (SD card
 * volumes like `1A2B-3C4D:`) yields no RELATIVE_PATH and is served by the SAF route.
 */
fun parseChosenDocId(docId: String): Pair<String?, String> {
    val parts = docId.split(':', limit = 2)
    val volume = parts[0]
    val path = parts.getOrNull(1)?.trim('/').orEmpty()
    val relativePath =
        when {
            path.isBlank() -> null
            volume == "primary" -> "$path/"
            volume == "home" -> "Documents/$path/"
            else -> null
        }
    val label =
        when {
            path.isNotBlank() -> path.substringAfterLast('/')
            volume == "primary" -> "Internal storage"
            volume == "home" -> "Documents"
            else -> "SD card"
        }
    return relativePath to label
}
