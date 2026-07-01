package com.appblish.calculatorvault.applock

/**
 * The board-specified first-run **Suggested** set (flow-logic §5, confirmed 2026-07-02):
 * messaging, the app store, email, and social apps are pre-checked above the full list —
 * framed calmly, never as a "high risk!" scare banner. We only ever suggest apps that are
 * *actually installed*, so [suggestedPackages] filters the candidate set against the real
 * device inventory. Kept as a pure function so it is unit-tested without a PackageManager.
 */
object SuggestedApps {
    /**
     * Known-sensitive package names to pre-check when present. Grouped by the board's
     * categories: messaging, store, email, social, plus clearly-sensitive gallery / UPI /
     * finance in the same spirit ("extend to other clearly-sensitive installed apps").
     */
    val CANDIDATES: Set<String> =
        setOf(
            // Messaging
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.google.android.apps.messaging",
            // App store
            "com.android.vending",
            // Email
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            // Social
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.snapchat.android",
            "com.reddit.frontpage",
            // Gallery / photos
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.sec.android.gallery3d",
            // Finance / UPI
            "com.phonepe.app",
            "net.one97.paytm",
            "com.google.android.apps.nbu.paisa.user", // Google Pay
        )

    /**
     * The subset of [CANDIDATES] that is installed on this device, given the full set of
     * launchable [installedPackages]. This is the pre-checked "Suggested" section.
     */
    fun suggestedPackages(installedPackages: Set<String>): Set<String> = CANDIDATES.intersect(installedPackages)

    /** Whether [packageName] should appear (pre-checked) in the Suggested section. */
    fun isSuggested(packageName: String): Boolean = packageName in CANDIDATES
}
