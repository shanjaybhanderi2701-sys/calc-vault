package com.appblish.calculatorvault.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Thin wrapper over [DevicePolicyManager] for the **Prevent uninstall** switch. The Settings
 * screen asks this whether protection is currently active, launches the system
 * "activate device admin" consent screen to turn it on, and deactivates it directly to turn
 * it off. Activation must go through the OS consent dialog (an app can never silently grant
 * itself admin), so [activationIntent] returns the intent the UI launches.
 */
class PreventUninstallController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(appContext, VaultDeviceAdminReceiver::class.java)

    /** True once the user has granted device-admin — i.e. uninstall protection is live. */
    fun isActive(): Boolean = dpm.isAdminActive(admin)

    /** The system consent intent the UI launches to turn protection on. */
    fun activationIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Keeps the vault from being uninstalled without your PIN.",
            )
        }

    /** Turn protection off. Safe to call when already inactive. */
    fun deactivate() {
        if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
    }
}
