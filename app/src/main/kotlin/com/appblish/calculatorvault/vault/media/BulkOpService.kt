package com.appblish.calculatorvault.vault.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live progress of the current bulk vault operation (hide / restore), published by the
 * repository as it processes each item and observed by [BulkOpService]'s notification.
 * A single process-wide slot is enough: the repository serializes its batches, and the
 * UI (if it ever wants an in-app progress bar) can collect the same flow.
 */
object BulkOpProgress {
    /** One batch: [label] names the op ("Hiding files"), [done] of [total] items finished. */
    data class Progress(
        val label: String,
        val done: Int,
        val total: Int,
    )

    private val state = MutableStateFlow<Progress?>(null)

    /** Null when no bulk operation is running. */
    val progress: StateFlow<Progress?> = state.asStateFlow()

    /** Begin a batch of [total] items. */
    fun start(
        label: String,
        total: Int,
    ) {
        state.value = Progress(label = label, done = 0, total = total)
    }

    /** Mark [done] items complete (monotonic per batch; no-op if no batch is running). */
    fun update(done: Int) {
        state.value = state.value?.copy(done = done)
    }

    /** End the batch — [BulkOpService] observes the null and stops itself. */
    fun finish() {
        state.value = null
    }

    /**
     * Diagnostic reason for the FIRST failure in the current batch (APP-248): the hide
     * pipeline otherwise swallows per-item errors (catch → skip) and a locked vault
     * (return empty), so a board user who sees "N failed" has no way to tell WHY — a
     * blocker for diagnosing a device-specific failure we cannot reproduce on the CI
     * emulators. The repository clears this at the start of a batch and records the first
     * failure's short cause; [HideImportViewModel.hideSummaryText] appends it to the
     * "N failed" copy so the reason is visible on-device. First-write-wins so the summary
     * names the root failure, not a cascade.
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /** Record the first failure reason of the current batch (later calls are ignored). */
    fun reportFailure(reason: String) {
        if (lastFailureReason == null) lastFailureReason = reason
    }

    /** Clear the diagnostic reason at the start of a fresh batch. */
    fun clearFailure() {
        lastFailureReason = null
    }
}

/**
 * Foreground service that keeps bulk hide/restore alive (build spec §11): a batch of
 * large videos can outlive the user's attention span, and without a foreground service
 * the OS may kill the process mid-encrypt, stranding half a batch. The repository starts
 * this service around each batch and streams per-item progress through [BulkOpProgress];
 * the service renders it as a low-importance, non-dismissible "Processing N of M"
 * notification (channel [CHANNEL_ID], `dataSync` type) and stops itself when the batch
 * finishes.
 *
 * The service is deliberately dumb — no work happens here, so a failed start (background
 * restrictions, denied POST_NOTIFICATIONS on API 33+) never blocks the operation itself:
 * [start] swallows the failure and the repository carries on without the notification.
 */
class BulkOpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        createChannel()
        // startForeground must run promptly after startForegroundService (5s ANR window).
        val initial = buildNotification(BulkOpProgress.progress.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }
        if (watcher == null) {
            watcher =
                scope.launch {
                    BulkOpProgress.progress.collect { progress ->
                        if (progress == null) {
                            // Batch done (or was already done before we started): tear down.
                            // Stopping the service also removes the foreground notification.
                            stopSelf()
                        } else {
                            // notify() is dropped (not thrown) when POST_NOTIFICATIONS is
                            // denied on API 33+ — the op keeps running either way; the
                            // runCatching only guards OEM quirks.
                            runCatching {
                                notificationManager().notify(NOTIFICATION_ID, buildNotification(progress))
                            }
                        }
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(progress: BulkOpProgress.Progress?): Notification {
        val total = progress?.total ?: 0
        val done = (progress?.done ?: 0).coerceIn(0, total)
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress?.label ?: "Working…")
            .setContentText("Processing $done of $total")
            .setProgress(total, done, total == 0)
            // Not dismissible while the batch runs; LOW importance = silent, no heads-up.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(CHANNEL_ID, "Vault operations", NotificationManager.IMPORTANCE_LOW)
            notificationManager().createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "vault_ops"
        private const val NOTIFICATION_ID = 2251

        /**
         * Best-effort start around a bulk batch. Guarded: on API 26+ a background-started
         * app may be forbidden from startForegroundService — the batch must still run, so
         * failures are swallowed and only the progress notification is lost.
         */
        fun start(context: Context) {
            val intent = Intent(context, BulkOpService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
