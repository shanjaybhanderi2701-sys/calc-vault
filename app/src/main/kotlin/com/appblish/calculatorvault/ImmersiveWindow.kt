package com.appblish.calculatorvault

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen immersive edge-to-edge, matching the reference app (xlock). (APP-204)
 *
 * The window is drawn edge-to-edge (transparent system bars via [enableEdgeToEdge] +
 * [WindowCompat.setDecorFitsSystemWindows] `false`) and both the status and navigation bars
 * are hidden with sticky behaviour: a swipe from a screen edge reveals them transiently as a
 * translucent overlay and they auto-hide again. Because the bars are hidden their insets
 * report as zero, so every surface — the calculator disguise keypad, the vault Home/grid, the
 * PIN lock screen — lays out over the full display and can never be clipped by or collide
 * with the system navigation bar. This is the "hide system bars immersively" option from the
 * QA fix direction; it fixes all screens from one place rather than per-screen inset padding.
 */
fun ComponentActivity.applyImmersiveEdgeToEdge() {
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
