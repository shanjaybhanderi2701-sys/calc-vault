package com.appblish.calculatorvault.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Swaps the launcher appearance behind the **Disguise icon & name** switch. Two
 * `<activity-alias>` entries point at the same [com.appblish.calculatorvault.MainActivity]:
 * the default "Calculator" tile and an alternate plain "Calc" tile with a different icon.
 * Exactly one is ever enabled, so the home screen shows a single icon; toggling flips which.
 *
 * Enabling/disabling a launcher component is the OS-sanctioned way to change an app's icon
 * and name at runtime without a reinstall. Note the launcher may briefly drop and re-add the
 * icon while it re-indexes, and the current task can be finished if the alias it launched
 * from is disabled — acceptable for a deliberate settings action.
 */
object DisguiseManager {
    private const val DEFAULT_ALIAS = "com.appblish.calculatorvault.CalculatorDefaultAlias"
    private const val ALT_ALIAS = "com.appblish.calculatorvault.CalculatorAltAlias"

    /** True when the alternate ("Calc") disguise is the one currently showing. */
    fun isAlternateActive(context: Context): Boolean {
        val pm = context.packageManager
        return pm.getComponentEnabledSetting(alias(context, ALT_ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    /** Show the alternate tile when [enabled], otherwise the default one. */
    fun setAlternate(
        context: Context,
        enabled: Boolean,
    ) {
        val pm = context.packageManager
        // Enable the chosen alias first so there is never a window with zero launchers.
        setState(pm, alias(context, if (enabled) ALT_ALIAS else DEFAULT_ALIAS), enable = true)
        setState(pm, alias(context, if (enabled) DEFAULT_ALIAS else ALT_ALIAS), enable = false)
    }

    private fun alias(
        context: Context,
        name: String,
    ) = ComponentName(context.applicationContext, name)

    private fun setState(
        pm: PackageManager,
        component: ComponentName,
        enable: Boolean,
    ) {
        val state =
            if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
