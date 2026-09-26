package com.iamode.app.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Gender
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {

    val state: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    fun setName(v: String) = edit { it.copy(myName = v) }
    fun setGender(v: Gender) = edit { it.copy(gender = v) }
    fun setLanguage(v: LanguageCode) = edit { it.copy(defaultLanguage = v) }
    fun setScript(v: Script) = edit { it.copy(defaultScript = v) }
    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        settings.update { it.copy(onboardingDone = true) }
        onDone()
    }

    private fun edit(f: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settings.update(f) }
    }
}
