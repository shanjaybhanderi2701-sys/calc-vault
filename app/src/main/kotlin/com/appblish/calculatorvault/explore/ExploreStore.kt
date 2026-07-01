package com.appblish.calculatorvault.explore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A secure note. Body is plain text in Phase 4; encryption-at-rest lands in Phase 5. */
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    val updatedAt: Long,
)

/** A domain the Private Browser refuses to load while [enabled]. */
data class BlockedSite(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,
    val enabled: Boolean = true,
)

/** One installed app whose notifications the vault suppresses when [hidden]. */
data class NotificationRule(
    val packageName: String,
    val label: String,
    val hidden: Boolean,
)

/** The Fake Password (decoy) configuration surfaced under Explore. */
data class FakePasswordState(
    val enabled: Boolean = false,
    val pin: String = "",
    val hint: String = "",
) {
    val configured: Boolean get() = pin.length >= MIN_PIN_LENGTH

    companion object {
        const val MIN_PIN_LENGTH = 4
    }
}

/**
 * A tiny in-memory backing store for the Explore / Quick Tools. It mirrors the vault's
 * [com.appblish.calculatorvault.vault.VaultGraph] service-locator style: one process-wide
 * singleton so a tool's state (notes, blocklist, notification rules, fake password) stays
 * consistent as the user pushes and pops the tool screens within a session.
 *
 * State lives only for the session here — encrypted persistence for these surfaces is a
 * Phase 5 hardening concern (APP-162), matching how the vault content repo started
 * in-memory before its device-backed store landed. The public API is stable so the
 * persistent implementation can drop in behind it.
 */
object ExploreStore {
    // --- Notes -------------------------------------------------------------
    private val _notes = MutableStateFlow(seedNotes())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun upsertNote(
        id: String?,
        title: String,
        body: String,
        now: Long,
    ): String {
        val trimmedTitle = title.trim().ifBlank { "Untitled note" }
        val existing = id?.let { current -> _notes.value.firstOrNull { it.id == current } }
        return if (existing != null) {
            val updated = existing.copy(title = trimmedTitle, body = body, updatedAt = now)
            _notes.value = _notes.value.map { if (it.id == existing.id) updated else it }
            existing.id
        } else {
            val note = Note(title = trimmedTitle, body = body, updatedAt = now)
            _notes.value = listOf(note) + _notes.value
            note.id
        }
    }

    fun deleteNote(id: String) {
        _notes.value = _notes.value.filterNot { it.id == id }
    }

    // --- Website blocker ---------------------------------------------------
    private val _blockedSites = MutableStateFlow(seedBlocked())
    val blockedSites: StateFlow<List<BlockedSite>> = _blockedSites.asStateFlow()

    /** Normalizes a user string to a bare host: strips scheme, path, and leading www. */
    fun normalizeDomain(raw: String): String =
        raw
            .trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .trim()

    /** Adds a domain if valid and not already present. Returns false if rejected. */
    fun addBlockedSite(raw: String): Boolean {
        val domain = normalizeDomain(raw)
        if (domain.isBlank() || '.' !in domain) return false
        if (_blockedSites.value.any { it.domain == domain }) return false
        _blockedSites.value = _blockedSites.value + BlockedSite(domain = domain)
        return true
    }

    fun setBlockedEnabled(
        id: String,
        enabled: Boolean,
    ) {
        _blockedSites.value = _blockedSites.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun removeBlockedSite(id: String) {
        _blockedSites.value = _blockedSites.value.filterNot { it.id == id }
    }

    /** True when [host] (or any parent domain of it) is on the active blocklist. */
    fun isBlocked(host: String): Boolean {
        val h = normalizeDomain(host)
        if (h.isBlank()) return false
        return _blockedSites.value.any { it.enabled && (h == it.domain || h.endsWith(".${it.domain}")) }
    }

    // --- Hide notification -------------------------------------------------
    private val _hideAllNotifications = MutableStateFlow(false)
    val hideAllNotifications: StateFlow<Boolean> = _hideAllNotifications.asStateFlow()

    private val _notificationRules = MutableStateFlow(seedNotificationRules())
    val notificationRules: StateFlow<List<NotificationRule>> = _notificationRules.asStateFlow()

    fun setHideAllNotifications(hidden: Boolean) {
        _hideAllNotifications.value = hidden
    }

    fun setNotificationHidden(
        packageName: String,
        hidden: Boolean,
    ) {
        _notificationRules.value =
            _notificationRules.value.map { if (it.packageName == packageName) it.copy(hidden = hidden) else it }
    }

    // --- Fake password -----------------------------------------------------
    private val _fakePassword = MutableStateFlow(FakePasswordState())
    val fakePassword: StateFlow<FakePasswordState> = _fakePassword.asStateFlow()

    fun setFakePassword(
        pin: String,
        hint: String,
    ) {
        _fakePassword.value = FakePasswordState(enabled = true, pin = pin, hint = hint.trim())
    }

    fun setFakePasswordEnabled(enabled: Boolean) {
        _fakePassword.value = _fakePassword.value.copy(enabled = enabled)
    }

    private fun seedNotes(): List<Note> =
        listOf(
            Note(title = "Wi-Fi password", body = "Home: sunflower-42-maple", updatedAt = 0L),
            Note(title = "Passport number", body = "Renews 2029. Kept here, out of the gallery.", updatedAt = 0L),
        )

    private fun seedBlocked(): List<BlockedSite> = emptyList()

    private fun seedNotificationRules(): List<NotificationRule> =
        listOf(
            NotificationRule("com.whatsapp", "WhatsApp", hidden = false),
            NotificationRule("com.instagram.android", "Instagram", hidden = false),
            NotificationRule("com.google.android.gm", "Gmail", hidden = false),
            NotificationRule("com.facebook.orca", "Messenger", hidden = false),
        )
}
