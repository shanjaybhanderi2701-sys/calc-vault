package com.appblish.calculatorvault.vault.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.components.VaultModal
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.model.UnhideDestination

/*
 * The four single-photo action surfaces of Wave-1 W1-E2 (design §5–§9), built only from
 * the vault design tokens: Move-to sheet, Unhide dialog, Delete dialog (2-step), Property
 * dialog. Every surface is stateless — the caller ([PhotoActionsHost]) owns which one is
 * open and supplies the data + callbacks — so the same surfaces serve the single-item
 * (viewer) and the multi-select (W1-E3) paths without change.
 *
 * Copy strings are the design's final strings. Destructive red appears only on the Delete
 * surfaces (spec §1 / design §3 "action.destructive only on Delete").
 */

/** One selectable album (vault folder) in the Move-to sheet. `id == null` is the category root. */
data class AlbumOption(
    val id: String?,
    val name: String,
    val count: Int,
)

// ---------------------------------------------------------------------------
// §6 · Move-to sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToSheet(
    itemCount: Int,
    albums: List<AlbumOption>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onMove: (folderId: String?) -> Unit,
    modifier: Modifier = Modifier,
    // Album-level deltas (W2-E design §5) — the defaults keep the shipped W1 photo sheet:
    // a bespoke header ("Move "Camera" to…"), extra disabled rows (every album being
    // moved, badged "This album"), and the merge note revealed once a target is picked.
    title: String? = null,
    disabledIds: Set<String?> = emptySet(),
    disabledBadge: String = "Current",
    noteForTarget: ((AlbumOption) -> String)? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember {
        mutableStateOf(TextFieldValue(NEW_ALBUM_PREFILL, selection = TextRange(0, NEW_ALBUM_PREFILL.length)))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg).padding(bottom = spacing.xl)) {
            Text(
                text = title ?: if (itemCount == 1) "Move photo to…" else "Move $itemCount photos to…",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = spacing.md),
            )

            // Create-new-album — pinned first, accent-tinted, inline-expands to the §1.1
            // prefilled-"New album" field (terminology lock, APP-218 fold-in).
            if (creating) {
                val trimmed = newName.text.trim()
                val duplicate = albums.any { it.name.trim().equals(trimmed, ignoreCase = true) }
                AlbumNameField(
                    value = newName,
                    onValueChange = { newName = it },
                    error =
                        when {
                            trimmed.isEmpty() -> "Enter an album name"
                            duplicate -> "An album with this name already exists"
                            else -> null
                        },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                ) {
                    PillButton(
                        text = "Cancel",
                        onClick = {
                            creating = false
                            newName =
                                TextFieldValue(NEW_ALBUM_PREFILL, selection = TextRange(0, NEW_ALBUM_PREFILL.length))
                        },
                        style = PillButtonStyle.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton(
                        text = "Create",
                        onClick = {
                            onCreateFolder(trimmed)
                            creating = false
                            newName =
                                TextFieldValue(NEW_ALBUM_PREFILL, selection = TextRange(0, NEW_ALBUM_PREFILL.length))
                        },
                        enabled = trimmed.isNotEmpty() && !duplicate,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(VaultTheme.shapes.card)
                            .clickable { creating = true }
                            .padding(vertical = spacing.md, horizontal = spacing.sm),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
                    Spacer(Modifier.width(spacing.md))
                    Text("Create new album", style = VaultTheme.typography.bodyLarge, color = colors.accent)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.sm)
                    .heightIn(min = 1.dp)
                    .background(colors.divider)
            )

            if (albums.isEmpty()) {
                Text(
                    text = "No other albums yet — create one",
                    style = VaultTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(albums, key = { it.id ?: "__root__" }) { album ->
                        val isCurrent = album.id == currentFolderId || album.id in disabledIds
                        val isSelected = hasSelection && album.id == selectedFolderId && !isCurrent
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(VaultTheme.shapes.card)
                                    .background(if (isSelected) colors.accent.copy(alpha = 0.14f) else colors.surface)
                                    .clickable(enabled = !isCurrent) {
                                        selectedFolderId = album.id
                                        hasSelection = true
                                    }.padding(vertical = spacing.md, horizontal = spacing.sm),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(VaultTheme.shapes.card)
                                        .background(colors.surfaceVariant),
                            ) {
                                Icon(
                                    Icons.Filled.List,
                                    contentDescription = null,
                                    tint = if (isCurrent) colors.textDisabled else colors.textSecondary,
                                )
                            }
                            Spacer(Modifier.width(spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = VaultTheme.typography.bodyLarge,
                                    color = if (isCurrent) colors.textDisabled else colors.textPrimary,
                                )
                                Text(
                                    text = if (isCurrent) disabledBadge else "${album.count}",
                                    style = VaultTheme.typography.labelMedium,
                                    color = colors.textSecondary,
                                )
                            }
                            if (isSelected) {
                                RadioButton(selected = true, onClick = null)
                            }
                        }
                    }
                }
            }

            // §5 merge note ("promise up front"): the consequence of an album merge is
            // stated the moment a target is picked — never a surprise after confirm.
            val note =
                if (noteForTarget != null && hasSelection) {
                    albums.firstOrNull { it.id == selectedFolderId }?.let(noteForTarget)
                } else {
                    null
                }
            if (note != null) {
                Text(
                    text = "ⓘ $note",
                    style = VaultTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }

            PillButton(
                text = "Move here",
                onClick = { onMove(selectedFolderId) },
                enabled = hasSelection,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
            )
        }
    }
}

/** The §1.1 create-dialog prefill (APP-218 terminology lock): typed-over on first keystroke. */
const val NEW_ALBUM_PREFILL = "New album"

// ---------------------------------------------------------------------------
// §7 · Unhide dialog
// ---------------------------------------------------------------------------

/** Which un-hide destination the radio has selected. */
enum class UnhideChoice { ORIGINAL, CHOSEN }

@Composable
fun UnhideDialog(
    itemCount: Int,
    originalPath: String?,
    choice: UnhideChoice,
    chosenFolderLabel: String?,
    onChoiceChange: (UnhideChoice) -> Unit,
    onPickFolder: () -> Unit,
    onConfirm: (UnhideDestination) -> Unit,
    onDismiss: () -> Unit,
    // Album-level deltas (W2-E design §6) — defaults keep the shipped W1 photo dialog.
    // An album is not one place, so the album variant retitles the dialog, adds the
    // "N photos will leave the vault" lead-in, and pluralizes the original-destination
    // row ("Original locations" / "Each photo returns to where it came from").
    title: String? = null,
    bodyText: String? = null,
    originalTitle: String = "Original location",
    originalSubtitle: String? = null,
    fallbackNote: String = "If the original folder isn't available, we'll save to Downloads and tell you.",
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val confirmEnabled = choice == UnhideChoice.ORIGINAL || chosenFolderLabel != null

    VaultModal(
        title = title ?: if (itemCount == 1) "Unhide photo" else "Unhide $itemCount photos",
        confirmLabel = "Unhide",
        confirmEnabled = confirmEnabled,
        onConfirm = {
            val dest =
                if (choice == UnhideChoice.ORIGINAL || chosenFolderLabel == null) {
                    UnhideDestination.Original
                } else {
                    UnhideDestination.Chosen(chosenFolderLabel)
                }
            onConfirm(dest)
        },
        onDismiss = onDismiss,
        content = {
            Text(
                text = bodyText ?: "Where should we put them?",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = spacing.md),
            )
            RadioRow(
                selected = choice == UnhideChoice.ORIGINAL,
                title = originalTitle,
                subtitle = originalSubtitle ?: originalPath?.trim('/')?.ifBlank { null } ?: "Original folder",
                onClick = { onChoiceChange(UnhideChoice.ORIGINAL) },
            )
            RadioRow(
                selected = choice == UnhideChoice.CHOSEN,
                title = "Choose a folder…",
                subtitle = chosenFolderLabel,
                onClick = {
                    onChoiceChange(UnhideChoice.CHOSEN)
                    onPickFolder()
                },
            )
            Text(
                text = fallbackNote,
                style = VaultTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.md),
            )
        },
    )
}

@Composable
private fun RadioRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = spacing.xs),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = VaultTheme.typography.bodyLarge, color = colors.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = VaultTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// §8 · Delete dialog — 2-step (Bin default, Permanent behind a second confirm)
// ---------------------------------------------------------------------------

/** Which step of the 2-step delete flow is showing. */
enum class DeleteStep { CHOICE, CONFIRM_PERMANENT }

@Composable
fun DeleteDialog(
    itemCount: Int,
    step: DeleteStep,
    onMoveToBin: () -> Unit,
    onChoosePermanent: () -> Unit,
    onConfirmPermanent: () -> Unit,
    onDismiss: () -> Unit,
    // Album-level deltas (W2-E design §7) — defaults keep the shipped W1 photo dialog.
    // The album variant spells out album + contents semantics ("The album and its 91
    // photos move to the Recycle Bin…") so "delete the album" is never misread.
    choiceTitle: String? = null,
    choiceMessage: String? = null,
    permanentBody: String? = null,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    val noun = if (itemCount == 1) "photo" else "photos"

    when (step) {
        DeleteStep.CHOICE ->
            VaultModal(
                title = choiceTitle ?: if (itemCount == 1) "Delete photo?" else "Delete $itemCount photos?",
                message =
                    choiceMessage
                        ?: "Items in the Recycle Bin stay recoverable for 30 days, then delete forever.",
                confirmLabel = "Move to Recycle Bin",
                onConfirm = onMoveToBin,
                onDismiss = onDismiss,
                content = {
                    TextButton(onClick = onChoosePermanent, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Delete permanently",
                            style = VaultTheme.typography.labelLarge,
                            color = colors.destructive,
                        )
                    }
                },
            )
        DeleteStep.CONFIRM_PERMANENT ->
            VaultModal(
                title = "Delete permanently?",
                confirmLabel = "Delete forever",
                confirmStyle = PillButtonStyle.Destructive,
                onConfirm = onConfirmPermanent,
                onDismiss = onDismiss,
                content = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.destructive)
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            text =
                                permanentBody ?: (
                                    "This securely erases $itemCount $noun from the vault. " +
                                        "They cannot be recovered."
                                ),
                            style = VaultTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )
                    }
                },
            )
    }
}

// ---------------------------------------------------------------------------
// §9 · Property dialog — read-only, all values from the encrypted index
// ---------------------------------------------------------------------------

@Composable
fun PropertyDialog(
    title: String,
    rows: List<PropertyRow>,
    onDismiss: () -> Unit,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    // Read-only detail card with a single Close (design §9) — not the 2-button VaultModal.
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = VaultTheme.shapes.card, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(spacing.xl)) {
                Text(text = title, style = VaultTheme.typography.titleLarge, color = colors.textPrimary)
                Spacer(Modifier.size(spacing.lg))
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
                        Text(
                            text = row.label,
                            style = VaultTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.width(112.dp),
                        )
                        Text(
                            text = row.value,
                            style = VaultTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                PillButton(
                    text = "Close",
                    onClick = onDismiss,
                    style = PillButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun UnhideDialogPreview() {
    CalculatorVaultTheme {
        UnhideDialog(
            itemCount = 4,
            originalPath = "DCIM/Camera/",
            choice = UnhideChoice.ORIGINAL,
            chosenFolderLabel = null,
            onChoiceChange = {},
            onPickFolder = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun DeleteDialogChoicePreview() {
    CalculatorVaultTheme {
        DeleteDialog(
            itemCount = 4,
            step = DeleteStep.CHOICE,
            onMoveToBin = {},
            onChoosePermanent = {},
            onConfirmPermanent = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun DeleteDialogPermanentPreview() {
    CalculatorVaultTheme {
        DeleteDialog(
            itemCount = 4,
            step = DeleteStep.CONFIRM_PERMANENT,
            onMoveToBin = {},
            onChoosePermanent = {},
            onConfirmPermanent = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun PropertyDialogPreview() {
    CalculatorVaultTheme {
        PropertyDialog(
            title = "Details",
            rows =
                listOf(
                    PropertyRow("Name", "IMG_2043.jpg"),
                    PropertyRow("Format", "JPEG"),
                    PropertyRow("Original", "DCIM/Camera"),
                    PropertyRow("Size", "4.2 MB"),
                    PropertyRow("Resolution", "4032 × 3024"),
                ),
            onDismiss = {},
        )
    }
}

/** Centered, muted em-dash helper kept for symmetry with other previews. */
@Preview
@Composable
private fun EmptyPropertyPreview() {
    CalculatorVaultTheme {
        Box(Modifier.size(1.dp), contentAlignment = Alignment.Center) {
            Text(PhotoProperties.UNKNOWN, textAlign = TextAlign.Center)
        }
    }
}
