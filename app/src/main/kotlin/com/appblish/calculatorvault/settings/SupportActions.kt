package com.appblish.calculatorvault.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Real OS actions behind the Settings → **Support** rows (deck: Community/Support section —
 * How it works, Rate, Share, Privacy policy, Feedback). These fire standard Android intents
 * so the rows are genuinely functional rather than dead placeholders. Each call degrades
 * gracefully with a Toast if no handler app is present (e.g. no Play Store on an emulator).
 */
object SupportActions {
    private const val PRIVACY_URL = "https://appblish.com/calcvault/privacy"
    private const val FEEDBACK_EMAIL = "support@appblish.com"

    /** Open the app's Play Store listing so the owner can leave a rating. */
    fun rate(context: Context) {
        val pkg = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    /** Share the app via the system share sheet. */
    fun share(context: Context) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Keep your private photos, videos and files behind a working calculator — CalcVault.",
                )
            }
        launch(context, Intent.createChooser(send, "Share CalcVault"))
    }

    /** Open the hosted privacy policy in the browser. */
    fun privacyPolicy(context: Context) = openUrl(context, PRIVACY_URL)

    /** Compose a feedback email to the support address. */
    fun feedback(context: Context) {
        val email =
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "CalcVault feedback")
            }
        launch(context, email)
    }

    private fun openUrl(
        context: Context,
        url: String,
    ) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(
        context: Context,
        intent: Intent,
    ) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app available to handle this", Toast.LENGTH_SHORT).show()
        }
    }
}
