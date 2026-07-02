package com.appblish.calculatorvault.applock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.intruder.IntruderEvent
import com.appblish.calculatorvault.ui.components.PillButton
import com.appblish.calculatorvault.ui.components.PillButtonStyle
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Intruder Selfie log (deck section H — "Intruder Selfie"): a reverse-chronological list
 * of break-in attempts, each with the captured front-camera photo and a **per-app badge**
 * naming which locked app the intruder tried to open, plus when. Warm, content-first empty
 * state per the taste guide (APP-136) — no scare framing.
 */
@Composable
fun IntruderLogScreen(
    events: List<IntruderEvent>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = VaultTheme.spacing
    Column(modifier = modifier.fillMaxSize()) {
        VaultTopBar(title = "Intruder Selfie", onBack = onBack)

        if (events.isEmpty()) {
            IntruderEmptyState(modifier = Modifier.fillMaxSize())
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(events, key = { it.id }) { event -> IntruderRow(event) }
        }
        PillButton(
            text = "Clear log",
            onClick = onClear,
            style = PillButtonStyle.Destructive,
            modifier = Modifier.padding(spacing.lg),
        )
    }
}

@Composable
private fun IntruderRow(event: IntruderEvent) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .clip(VaultTheme.shapes.card)
                .background(colors.surface)
                .padding(spacing.md),
    ) {
        IntruderPhoto(path = event.photoPath)
        Spacer(Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = event.packageName, label = event.appLabel, size = 24.dp)
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = event.appLabel,
                    style = VaultTheme.typography.titleSmall,
                    color = colors.textPrimary,
                )
            }
            Text(
                text = "Failed unlock attempt",
                style = VaultTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.xs),
            )
            Text(
                text = formatTime(event.timestampMs),
                style = VaultTheme.typography.labelMedium,
                color = colors.textDisabled,
            )
        }
    }
}

@Composable
private fun IntruderPhoto(path: String?) {
    val colors = VaultTheme.colors
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value =
            path?.takeIf { it.startsWith("/") }?.let { p ->
                runCatching {
                    val file = File(p)
                    if (file.exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null
                }.getOrNull()
            }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(64.dp)
                .clip(VaultTheme.shapes.thumbnail)
                .background(colors.surfaceVariant),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Intruder photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.Face, contentDescription = null, tint = colors.textDisabled)
        }
    }
}

@Composable
private fun IntruderEmptyState(modifier: Modifier = Modifier) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier = modifier.padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
    ) {
        RoundIconBadge(icon = Icons.Filled.Face)
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = "No break-in attempts",
            style = VaultTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Text(
            text = "If someone enters the wrong PIN on a locked app, their photo shows up here.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatTime(ms: Long): String = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(ms))
