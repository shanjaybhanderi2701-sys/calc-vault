package com.appblish.calculatorvault.applock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's launchable apps for the AppLock list and picker. Query is done through
 * the launcher intent (only apps with a launcher entry — never a raw dump of every package),
 * excludes our own package (locking the vault behind itself would be a trap), and merges in
 * the persisted locked set. Icons are loaded on demand and rasterized to an [ImageBitmap] so
 * Compose can draw them without Coil.
 */
class AppInventory(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    /**
     * All launchable apps, alphabetized, with [InstalledApp.locked] reflecting [lockedPackages]
     * and [InstalledApp.suggested] the board's first-run set. Runs off the main thread.
     */
    suspend fun installedApps(lockedPackages: Set<String>): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            resolved
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != appContext.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        locked = info.packageName in lockedPackages,
                        suggested = SuggestedApps.isSuggested(info.packageName),
                        system = info.isSystem(),
                    )
                }.sortedBy { it.label.lowercase() }
                .toList()
        }

    /** The installed subset of the board's suggested set (for the pre-checked section). */
    suspend fun suggestedPackages(): Set<String> =
        withContext(Dispatchers.IO) {
            val installed =
                pm
                    .queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        0,
                    ).mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            SuggestedApps.suggestedPackages(installed)
        }

    /** Load [packageName]'s launcher icon as an [ImageBitmap], or null if it can't be read. */
    suspend fun icon(packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(packageName).toImageBitmap() }.getOrNull()
        }

    private fun ApplicationInfo.isSystem(): Boolean = (flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun Drawable.toImageBitmap(): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
        val w = intrinsicWidth.takeIf { it > 0 } ?: ICON_PX
        val h = intrinsicHeight.takeIf { it > 0 } ?: ICON_PX
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp.asImageBitmap()
    }

    private companion object {
        const val ICON_PX = 96
    }
}
