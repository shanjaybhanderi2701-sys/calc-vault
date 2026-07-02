package com.appblish.calculatorvault.vault.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * All Files Access — the single permission the public `.CalcVault/` storage needs, and how
 * to check / request it. Mirrors `applock.AppLockPermissions`: a point-of-need primer
 * ([com.appblish.calculatorvault.vault.storage.ui.StoragePermissionScreen]), calm copy, a
 * denial just returns to the primer (flow-logic §4). No scare framing.
 *
 * - **API 30+ (R and up):** `MANAGE_EXTERNAL_STORAGE`, checked via
 *   [Environment.isExternalStorageManager] and granted from the special-access settings
 *   screen (there is no runtime-dialog for this permission).
 * - **API 24–29:** legacy external storage — `WRITE_EXTERNAL_STORAGE`, a normal runtime
 *   permission, is sufficient with `requestLegacyExternalStorage`.
 */
object StoragePermissions {
    /** True once the vault can write its hidden public folder. */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * The settings intent that grants All Files Access on API 30+. Prefers the
     * app-scoped screen (lands directly on our toggle); falls back to the global list if
     * the OEM does not honor the package Uri. `null` below API 30 — callers use the
     * runtime `WRITE_EXTERNAL_STORAGE` request there instead.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** Whether this SDK level grants storage through the runtime `WRITE_EXTERNAL_STORAGE` dialog. */
    fun usesRuntimeWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
}
