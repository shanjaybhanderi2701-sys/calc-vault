package com.appblish.calculatorvault.navigation

import android.content.pm.ActivityInfo

/**
 * The whole-app orientation policy (APP-420). The disguise, auth spine, vault home, every
 * grid, settings, recycle bin, pickers and dialogs stay **portrait only** — a vault that
 * flips to landscape mid-grid is jarring and off-parity with the reference app. The sole
 * exceptions are the full-screen media *viewing* surfaces: the gallery pager (photo viewer +
 * video player, [VaultDestinations.VIEWER]) and the folder slideshow
 * ([VaultDestinations.SLIDESHOW]), which may rotate to portrait **or** landscape so a
 * landscape photo/video fills the screen.
 *
 * The decision is a pure function of the current nav route so it is unit-testable in
 * isolation from Compose/lifecycle (mirrors [SessionLock]); [VaultNavHost] applies the
 * result to `Activity.requestedOrientation` whenever the settled route changes.
 *
 * Interplay with the rest of the player:
 *  - The video player's manual rotate control cycles a content `rotationZ` transform
 *    (VideoScaleMath.nextRotation) — it never touches device orientation, so it keeps
 *    working unchanged inside the [SCREEN_ORIENTATION_SENSOR] viewer (APP-420 req #4).
 *  - MainActivity declares `configChanges="orientation|screenSize|…"` (APP-381), so both a
 *    physical rotation AND a `requestedOrientation` switch arrive as a configuration change
 *    (Compose relayout), never an activity recreate. A video therefore keeps playing from
 *    the same position across a rotation (APP-420 req #5). Because the effect that applies
 *    this is keyed on the route, a physical rotation — which leaves the route unchanged —
 *    never re-fires it and so never fights the sensor.
 */
internal object OrientationPolicy {
    /**
     * Route bases (the segment before the first `/` argument or `?` query) of the
     * full-screen media viewers that are allowed to rotate. Derived from the route patterns
     * so it stays in lock-step with [VaultDestinations] if a pattern ever changes.
     */
    private val ROTATION_ALLOWED_BASES: Set<String> =
        setOf(VaultDestinations.VIEWER, VaultDestinations.SLIDESHOW).map { it.base() }.toSet()

    /** The base segment of a route pattern/instance, e.g. `viewer/{category}/…` → `viewer`. */
    private fun String.base(): String = substringBefore('/').substringBefore('?')

    /** True only on the full-screen media viewers (photo viewer, video player, slideshow). */
    fun allowsRotation(route: String?): Boolean = route != null && route.base() in ROTATION_ALLOWED_BASES

    /**
     * The `ActivityInfo.SCREEN_ORIENTATION_*` value for [route]. Viewers follow the device
     * sensor (portrait + landscape) so a full-screen media player rotates like any gallery
     * even when the system auto-rotate lock is on; every other surface — and an
     * as-yet-unresolved null route — is pinned to [ActivityInfo.SCREEN_ORIENTATION_PORTRAIT].
     */
    fun forRoute(route: String?): Int =
        if (allowsRotation(route)) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
}
