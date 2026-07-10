package com.appblish.calculatorvault.recovery

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.appblish.calculatorvault.vault.VaultSession
import com.appblish.calculatorvault.vault.crypto.InMemoryRecoveryReKeyer
import com.appblish.calculatorvault.vault.crypto.RecoveryReKeyer
import com.appblish.calculatorvault.vault.crypto.RecoverySecrets
import com.appblish.calculatorvault.vault.crypto.VaultKeyFile
import com.appblish.calculatorvault.vault.crypto.VaultKeyFileRecoveryReKeyer
import com.appblish.calculatorvault.vault.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The W2 seam over the W1 recovery envelope ([VaultKeyFile]). It answers two questions the
 * setup + entry-point UI needs — *is recovery configured?* and *configure it now* — without
 * leaking crypto details into the ViewModels. All work operates purely on the small wrap
 * slots of the **current session's** key file (PIN Recovery spec §1): setting up recovery
 * adds Wrap B (security answer) and Wrap C (recovery code) for the same immutable DEK the
 * PIN already unwraps, so no blob is ever re-encrypted.
 *
 * `11223344 =` and the 3-failed-attempt affordance are pure navigation doorways and never
 * touch this manager — opening the recovery screen resets nothing (§1.4). The only mutation
 * here is [setUp], which the setup flow calls after the user has recorded both secrets.
 */
interface RecoveryManager {
    /** True once BOTH recovery wraps (security answer + recovery code) exist for this vault. */
    suspend fun isConfigured(): Boolean

    /**
     * The security-question prompt the user chose at setup (for the recovery-entry and
     * Settings surfaces, W3/W4), or `null` if recovery is not configured / the prompt is
     * unknown. Non-secret metadata — the answer itself is never stored, only its Wrap B.
     */
    suspend fun configuredQuestion(): String?

    /**
     * Configure recovery for the current session: derive Wrap B from [securityAnswer] and
     * Wrap C from [recoveryCode] against the DEK the session PIN already unwraps, persist the
     * envelope atomically, and record the non-secret [question] prompt for later display.
     * Rejects a secret that normalizes to nothing (W1 §1 advisory carried into W2 — a
     * degenerate answer must never create a silently-unreachable wrap). Throws
     * [IllegalStateException] when there is no live vault session, and
     * [VaultKeyFile.WrongPassphraseException] if the session PIN no longer unwraps the vault.
     */
    suspend fun setUp(
        question: String,
        securityAnswer: String,
        recoveryCode: String,
    )
}

/**
 * Production [RecoveryManager] over the real, survive-uninstall key file of whatever vault
 * the session is in ([VaultStorage.keyFile] defaults to [VaultSession.namespace]). Recovery
 * setup + banner are gated to the real vault by the UI (they hang off
 * [com.appblish.calculatorvault.auth.CredentialStore.hasOpenedRealVault]); the manager
 * itself simply operates on the current namespace so it is decoy-safe by construction.
 */
class VaultKeyFileRecoveryManager(
    context: Context,
) : RecoveryManager {
    private val appContext = context.applicationContext

    private fun keyFile(): VaultKeyFile = VaultKeyFile(VaultStorage.keyFile(appContext))

    // The chosen question prompt is non-secret display metadata; it lives in the same
    // hardware-keystore-backed encrypted prefs family as the other settings (never as
    // plaintext prefs). Keyed by namespace so a decoy's prompt can't shadow the real one.
    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun questionKey(): String = "$KEY_QUESTION_PREFIX${VaultSession.namespace}"

    override suspend fun isConfigured(): Boolean =
        withContext(Dispatchers.IO) {
            val file = keyFile()
            file.exists() && file.isRecoveryConfigured()
        }

    override suspend fun configuredQuestion(): String? =
        withContext(Dispatchers.IO) {
            if (isConfigured()) prefs.getString(questionKey(), null) else null
        }

    override suspend fun setUp(
        question: String,
        securityAnswer: String,
        recoveryCode: String,
    ) = withContext(Dispatchers.IO) {
        val pin =
            VaultSession.passphrase
                ?: error("Cannot set up recovery without an active vault session")
        require(question.isNotBlank()) { "Security question is blank" }
        require(RecoverySecrets.normalizeAnswer(securityAnswer).isNotEmpty()) {
            "Security answer is empty after normalization"
        }
        require(RecoverySecrets.normalizeRecoveryCode(recoveryCode).isNotEmpty()) {
            "Recovery code is empty after normalization"
        }
        // Write the envelope first — the durable, survive-uninstall source of truth — then
        // record the prompt; if the envelope write throws, no orphan prompt is left behind.
        keyFile().setUpRecovery(pin, securityAnswer, recoveryCode)
        prefs.edit().putString(questionKey(), question.trim()).apply()
    }

    private companion object {
        const val PREFS_NAME = "calcvault_recovery_meta"
        const val KEY_QUESTION_PREFIX = "recovery_question_"
    }
}

/**
 * In-memory [RecoveryManager] for `@Preview` / unit tests / an uninitialised graph: never
 * touches disk, so screens render and tests that don't exercise the real envelope stay
 * hermetic. [setUp] flips [isConfigured] so banner/setup UI can be driven end-to-end.
 */
class InMemoryRecoveryManager(
    configured: Boolean = false,
) : RecoveryManager {
    @Volatile
    private var configured: Boolean = configured

    @Volatile
    private var question: String? = null

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun configuredQuestion(): String? = if (configured) question else null

    override suspend fun setUp(
        question: String,
        securityAnswer: String,
        recoveryCode: String,
    ) {
        require(question.isNotBlank()) { "Security question is blank" }
        require(RecoverySecrets.normalizeAnswer(securityAnswer).isNotEmpty()) {
            "Security answer is empty after normalization"
        }
        require(RecoverySecrets.normalizeRecoveryCode(recoveryCode).isNotEmpty()) {
            "Recovery code is empty after normalization"
        }
        this.question = question.trim()
        configured = true
    }
}

/**
 * Tiny service locator for the [RecoveryManager] singleton, mirroring
 * [com.appblish.calculatorvault.vault.VaultGraph]. [init] installs the device-backed
 * manager from the Application; an uninitialised getter (Compose `@Preview`, unit tests)
 * falls back to an [InMemoryRecoveryManager]. [override] lets instrumented tests inject a
 * manager backed by a temp key file.
 */
object RecoveryGraph {
    @Volatile
    private var manager: RecoveryManager? = null

    @Volatile
    private var reKeyer: RecoveryReKeyer? = null

    @Volatile
    private var attemptStore: RecoveryAttemptStore? = null

    /** Install the device-backed manager + reset seam + backoff store. Idempotent. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (manager == null) {
            synchronized(this) {
                if (manager == null) manager = VaultKeyFileRecoveryManager(app)
            }
        }
        if (reKeyer == null) {
            synchronized(this) {
                if (reKeyer == null) reKeyer = VaultKeyFileRecoveryReKeyer(app)
            }
        }
        if (attemptStore == null) {
            synchronized(this) {
                if (attemptStore == null) attemptStore = RecoveryAttemptStores.device(app)
            }
        }
    }

    /** Replace the manager (instrumented tests inject a temp-file-backed one). */
    fun override(manager: RecoveryManager) {
        this.manager = manager
    }

    /** Replace the reset seam (instrumented / preview). */
    fun overrideReKeyer(reKeyer: RecoveryReKeyer) {
        this.reKeyer = reKeyer
    }

    /** Replace the backoff attempt store (instrumented / preview). */
    fun overrideAttemptStore(attemptStore: RecoveryAttemptStore) {
        this.attemptStore = attemptStore
    }

    val recoveryManager: RecoveryManager
        get() =
            manager ?: synchronized(this) {
                manager ?: InMemoryRecoveryManager().also { manager = it }
            }

    /** The W3 recovery unlock + PIN-reset seam; an in-memory dead-end fallback if uninitialised. */
    val recoveryReKeyer: RecoveryReKeyer
        get() =
            reKeyer ?: synchronized(this) {
                reKeyer ?: InMemoryRecoveryReKeyer().also { reKeyer = it }
            }

    /** The survive-uninstall backoff store; a non-persistent fallback if uninitialised. */
    val recoveryAttemptStore: RecoveryAttemptStore
        get() =
            attemptStore ?: synchronized(this) {
                attemptStore ?: InMemoryRecoveryAttemptStore().also { attemptStore = it }
            }
}
