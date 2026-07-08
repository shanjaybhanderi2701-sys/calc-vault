package com.appblish.calculatorvault.vault.actions

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var chosenFolder by remember { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val relPath = uri?.let(::treeUriToRelativePath)
            if (relPath != null) {
                chosenFolder = relPath
                choice = UnhideChoice.CHOSEN
            } else {
                // User backed out without picking — fall back to the safe default choice.
                choice = UnhideChoice.ORIGINAL
            }
        }
    UnhideDialog(
        itemCount = 1,
        originalPath = item.relativePath,
        choice = choice,
        chosenFolderLabel = chosenFolder,
        onChoiceChange = { choice = it },
        onPickFolder = { picker.launch(null) },
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
 * Best-effort translation of a SAF tree Uri to a MediaStore RELATIVE_PATH. A tree document
 * id looks like `primary:Pictures/Vault`; we keep the part after the volume prefix and add a
 * trailing slash. Returns null for non-primary volumes / unparseable ids, so the caller
 * treats "Choose a folder…" as not-yet-picked rather than writing to a bogus path.
 */
fun treeUriToRelativePath(treeUri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val parts = docId.split(':', limit = 2)
    if (parts.size != 2 || parts[0] != "primary") return null
    val path = parts[1].trim('/')
    if (path.isBlank()) return null
    return "$path/"
}
