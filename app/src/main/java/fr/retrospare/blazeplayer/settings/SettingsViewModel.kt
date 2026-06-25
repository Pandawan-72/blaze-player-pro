package fr.retrospare.blazeplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    val subtitleLanguage = userRepository.subtitleLanguageFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, "auto")

    fun setSubtitleLanguage(language: String) {
        viewModelScope.launch { userRepository.setSubtitleLanguage(language) }
    }

    fun setPlayerTheme(theme: String) {
        viewModelScope.launch { userRepository.setPlayerTheme(theme) }
    }
}
