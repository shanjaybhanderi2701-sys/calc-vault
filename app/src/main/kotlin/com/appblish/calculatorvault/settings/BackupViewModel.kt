package com.appblish.calculatorvault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblish.calculatorvault.auth.AuthGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val backupPassword: String = "",
    val restorePassword: String = "",
    val restoreBlob: String = "",
    /** The freshly-created backup blob, ready to copy or share. */
    val createdBlob: String? = null,
    val message: String? = null,
    val error: String? = null,
    val working: Boolean = false,
)

/**
 * Backs Settings → Backup & restore. Create produces the encrypted [BackupManager] blob the
 * owner can copy/share off-device; Restore takes a pasted blob + its password and rewrites
 * all credential and settings state. The password never leaves this flow.
 */
class BackupViewModel(
    private val backupManager: BackupManager,
) : ViewModel() {
    constructor() : this(BackupManager(AuthGraph.credentialStore, SettingsGraph.settingsStore))

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun onBackupPasswordChange(value: String) = _state.update { it.copy(backupPassword = value, error = null) }

    fun onRestorePasswordChange(value: String) = _state.update { it.copy(restorePassword = value, error = null) }

    fun onRestoreBlobChange(value: String) = _state.update { it.copy(restoreBlob = value, error = null) }

    fun createBackup() {
        val password = _state.value.backupPassword
        _state.update { it.copy(working = true, message = null, error = null, createdBlob = null) }
        viewModelScope.launch {
            try {
                val blob = backupManager.createBackup(password)
                _state.update {
                    it.copy(
                        createdBlob = blob,
                        message = "Backup ready. Copy or share it somewhere safe.",
                        working = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't create the backup.", working = false) }
            }
        }
    }

    fun restore() {
        val blob = _state.value.restoreBlob.trim()
        val password = _state.value.restorePassword
        _state.update { it.copy(working = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                backupManager.restoreBackup(blob, password)
                _state.update {
                    it.copy(
                        message = "Restore complete. Your PIN and settings are back.",
                        working = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't restore that backup.", working = false) }
            }
        }
    }
}
