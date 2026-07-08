package com.appblish.calculatorvault

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge-to-edge with VISIBLE system bars (APP-225 board findings P2–P4; supersedes the
 * APP-204 immersive hide-bars mode this file originally carried).
 *
 * The window is drawn edge-to-edge — [enableEdgeToEdge] calls
 * `WindowCompat.setDecorFitsSystemWindows(window, false)` under the hood — with the status
 * and navigation bars SHOWN and fully transparent. [SystemBarStyle.dark] matches the app's
 * dark-only theme: light (white) bar icons over the near-black canvas, and no forced
 * navigation-bar contrast scrim on API 29+. Because the bars stay visible their insets now
 * report real sizes, so composables are responsible for inset handling: [MainActivity] and
 * [com.appblish.calculatorvault.applock.LockScreenActivity] pad their whole content tree
 * with `WindowInsets.safeDrawing` at the root, which keeps every screen clear of the status
 * bar, display cutout, and navigation bar from one place while the root surface still
 * paints the canvas color behind the transparent bars.
 */
fun ComponentActivity.applyEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
}
