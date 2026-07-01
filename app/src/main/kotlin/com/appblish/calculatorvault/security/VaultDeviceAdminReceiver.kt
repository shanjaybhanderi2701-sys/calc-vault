package com.appblish.calculatorvault.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device-admin receiver behind the **Prevent uninstall** hardening switch. Android will not
 * let a user uninstall (or force-stop from the launcher long-press) an app that is an active
 * device administrator until the admin is first deactivated — which, in our flow, is only
 * reachable from inside the unlocked vault. This closes the "attacker long-presses the icon
 * and uninstalls the vault to destroy evidence" hole.
 *
 * We deliberately request only the minimal policy (force-lock); we never wipe the device.
 */
class VaultDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        // Admin activated from Settings → the Prevent-uninstall switch can now read it as on.
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent,
    ): CharSequence = "Turning this off removes the vault's uninstall protection. Anyone can then remove the app."
}
