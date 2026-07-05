package com.appblish.calculatorvault.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblish.calculatorvault.onboarding.LanguageSelectScreen

/**
 * Settings → App language (design sign-off S22): the onboarding S2 language list reused
 * as a settings page. Selecting moves the radio; Done persists and returns.
 */
@Composable
fun SettingsLanguageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LanguageSelectScreen(
        selected = state.settings.appLanguage,
        onSelect = viewModel::setAppLanguage,
        onDone = onBack,
        modifier = modifier,
    )
}
