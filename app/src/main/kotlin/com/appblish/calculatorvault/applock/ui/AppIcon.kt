package com.appblish.calculatorvault.applock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appblish.calculatorvault.applock.AppInventory
import com.appblish.calculatorvault.ui.theme.VaultTheme

/**
 * A launcher-icon chip for an installed app, loaded off the main thread from the
 * [AppInventory] and cached per package for the composition. Falls back to a green letter
 * chip while loading or when the icon can't be read (previews, restricted packages).
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val inventory = remember(context) { AppInventory(context.applicationContext) }
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = inventory.icon(packageName)
    }
    val colors = VaultTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceVariant),
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = label, modifier = Modifier.size(size * 0.66f))
        } else {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                style = VaultTheme.typography.titleMedium,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
