package fr.retrospare.blazeplayer.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkSharesViewModel @Inject constructor(
    private val networkRepository: NetworkRepository
) : ViewModel() {

    val shares: StateFlow<List<NetworkShare>> get() = _shares.asStateFlow()
    private val _shares = MutableStateFlow<List<NetworkShare>>(emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            networkRepository.getShares().collect { _shares.value = it }
        }
    }

    fun saveShare(share: NetworkShare) = viewModelScope.launch {
        networkRepository.saveShare(share)
        _message.value = "Chemin sauvegardé"
    }

    fun deleteShare(id: String) = viewModelScope.launch {
        networkRepository.deleteShare(id)
        _message.value = "Chemin supprimé"
    }

    fun setDefault(share: NetworkShare) = viewModelScope.launch {
        networkRepository.saveShare(share.copy(isDefault = true))
    }

    fun createShare(
        name: String, host: String, port: Int?, shareName: String,
        username: String?, password: String?, type: ShareType, isDefault: Boolean
    ) = networkRepository.createShare(name, host, port, shareName, username, password, type, isDefault)

    fun scanNetwork() {
        // TODO: SSDP/mDNS scan
        _message.value = "Scan non disponible sur émulateur"
    }

    fun clearMessage() { _message.value = null }
}
