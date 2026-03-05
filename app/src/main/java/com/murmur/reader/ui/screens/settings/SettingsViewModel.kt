package com.murmur.reader.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.murmur.reader.data.preferences.UserPreferences
import com.murmur.reader.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = prefsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { prefsRepository.setDarkTheme(dark) }
    fun setFontSize(sp: Float) = viewModelScope.launch { prefsRepository.setFontSize(sp) }
    fun setKeepScreenOn(keep: Boolean) = viewModelScope.launch { prefsRepository.setKeepScreenOn(keep) }
    fun setSerifFont(serif: Boolean) = viewModelScope.launch { prefsRepository.setSerifFont(serif) }
}
