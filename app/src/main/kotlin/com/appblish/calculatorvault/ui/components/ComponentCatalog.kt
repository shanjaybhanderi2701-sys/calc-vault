package com.appblish.calculatorvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.ui.theme.CalculatorVaultTheme
import com.appblish.calculatorvault.ui.theme.VaultTheme

// A single scrollable gallery of every shared component, rendered through the real
// theme. Doubles as the fidelity-review surface (compare against the SVG deck) and
// as living documentation of the component library.

private val sampleCategoryColors =
    listOf(
        Color(0xFF22C55E),
        Color(0xFF3B82F6),
        Color(0xFFF59E0B),
        Color(0xFF8B5CF6),
        Color(0xFFEC4899),
    )

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = VaultTheme.typography.labelLarge,
        color = VaultTheme.colors.textSecondary,
        modifier = Modifier.padding(top = VaultTheme.spacing.lg, bottom = VaultTheme.spacing.sm),
    )
}

@Composable
fun ComponentCatalog(modifier: Modifier = Modifier) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    var toggleOn by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }
    var navIndex by remember { mutableIntStateOf(0) }
    var showModal by remember { mutableStateOf(false) }
    val selected = remember { mutableStateOf(setOf("m2", "m4")) }

    Surface(color = colors.canvas, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
        ) {
            Text(
                text = "CalcVault Component Library",
                style = VaultTheme.typography.headlineMedium,
                color = colors.textPrimary,
            )

            SectionLabel("Pill buttons")
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                PillButton("Continue", onClick = {}, leadingIcon = Icons.Filled.Check)
                PillButton("Cancel", onClick = {}, style = PillButtonStyle.Secondary)
                PillButton(
                    "Delete permanently",
                    onClick = {},
                    style = PillButtonStyle.Destructive,
                    leadingIcon = Icons.Filled.Delete,
                )
            }

            SectionLabel("Category cards")
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                CategoryCard("Photos", 128, Icons.Filled.Star, sampleCategoryColors[0], {}, Modifier.weight(1f))
                CategoryCard("Videos", 12, Icons.Filled.PlayArrow, sampleCategoryColors[1], {}, Modifier.weight(1f))
                CategoryCard("Files", 1, Icons.Filled.List, sampleCategoryColors[2], {}, Modifier.weight(1f))
            }

            SectionLabel("List rows")
            ListRow(
                title = "WhatsApp",
                subtitle = "Locked",
                leadingIcon = Icons.Filled.Lock,
                leadingChipColor = sampleCategoryColors[3],
                trailing = RowTrailing.Toggle(toggleOn) { toggleOn = it },
            )
            ListRow(
                title = "Contacts",
                subtitle = "42 hidden",
                leadingIcon = Icons.Filled.Person,
                trailing = RowTrailing.Chevron(Icons.Filled.KeyboardArrowRight),
                onClick = {},
            )
            ListRow(
                title = "Recycle bin",
                leadingIcon = Icons.Filled.Delete,
                trailing = RowTrailing.Badge("3"),
                onClick = {},
            )

            SectionLabel("Calculator keys")
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                CalculatorKey("AC", CalcKeyStyle.Function, {}, Modifier.weight(1f))
                CalculatorKey("7", CalcKeyStyle.Digit, {}, Modifier.weight(1f))
                CalculatorKey("÷", CalcKeyStyle.Operator, {}, Modifier.weight(1f))
                CalculatorKey("=", CalcKeyStyle.Equals, {}, Modifier.weight(1f))
            }

            SectionLabel("Multi-select action bar")
            MultiSelectActionBar(
                selectedCount = 2,
                closeIcon = Icons.Filled.Close,
                onClose = {},
                actions =
                    listOf(
                        SelectionAction(Icons.Filled.Share, "Share") {},
                        SelectionAction(Icons.Filled.Delete, "Delete", destructive = true) {},
                    ),
            )

            SectionLabel("Date-grouped media grid")
            DateGroupedMediaGrid(
                items = sampleMedia(),
                selectionMode = true,
                selectedIds = selected.value,
                checkIcon = Icons.Filled.Check,
                onItemClick = { item ->
                    selected.value =
                        if (item.id in selected.value) {
                            selected.value - item.id
                        } else {
                            selected.value + item.id
                        }
                },
                onItemLongPress = {},
                modifier = Modifier.height(360.dp),
            )

            SectionLabel("Bottom nav")
            VaultBottomNav(
                items =
                    listOf(
                        NavItem("Vault", Icons.Filled.Home),
                        NavItem("AppLock", Icons.Filled.Lock),
                        NavItem("Explore", Icons.Filled.Search),
                    ),
                selectedIndex = navIndex,
                onSelect = { navIndex = it },
            )

            SectionLabel("FAB + expand menu")
            Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.sm)) {
                VaultFab(
                    icon = Icons.Filled.Add,
                    expanded = fabExpanded,
                    onExpandedChange = { fabExpanded = it },
                    actions =
                        listOf(
                            FabAction("Create folder", Icons.Filled.Add) {},
                            FabAction("Hide photos", Icons.Filled.Favorite) {},
                        ),
                    modifier = Modifier.padding(spacing.sm),
                )
            }

            SectionLabel("Modal")
            PillButton("Show modal", onClick = { showModal = true }, style = PillButtonStyle.Secondary)
        }
    }

    if (showModal) {
        VaultModal(
            title = "Create folder",
            message = "Name your new hidden folder.",
            confirmLabel = "Create",
            onConfirm = { showModal = false },
            onDismiss = { showModal = false },
        )
    }
}

private fun sampleMedia(): List<MediaItem> =
    listOf(
        MediaItem("m1", "Today", 500),
        MediaItem("m2", "Today", 499),
        MediaItem("m3", "Today", 498),
        MediaItem("m4", "Yesterday", 400),
        MediaItem("m5", "Yesterday", 399),
        MediaItem("m6", "12 Jun 2026", 300),
    )

@Preview(name = "Component catalog", showBackground = true, heightDp = 2200)
@Composable
private fun ComponentCatalogPreview() {
    CalculatorVaultTheme {
        ComponentCatalog()
    }
}
