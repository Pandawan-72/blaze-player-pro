package fr.retrospare.blazeplayer.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.network.NetworkScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkSharesViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val networkScanner: NetworkScanner
) : ViewModel() {

    /** Type de message plutôt que texte brut : le ViewModel n'a pas de Context pour traduire,
     *  c'est donc NetworkSharesFragment (qui en a un) qui fait la traduction à l'affichage. */
    enum class NetworkMessage { PATH_SAVED, PATH_DELETED, SCAN_UNAVAILABLE_EMULATOR }

    val shares: StateFlow<List<NetworkShare>> get() = _shares.asStateFlow()
    private val _shares = MutableStateFlow<List<NetworkShare>>(emptyList())

    private val _message = MutableStateFlow<NetworkMessage?>(null)
    val message: StateFlow<NetworkMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            networkRepository.getShares().collect { _shares.value = it }
        }
    }

    fun saveShare(share: NetworkShare) = viewModelScope.launch {
        networkRepository.saveShare(share)
        _message.value = NetworkMessage.PATH_SAVED
    }

    fun deleteShare(id: String) = viewModelScope.launch {
        networkRepository.deleteShare(id)
        _message.value = NetworkMessage.PATH_DELETED
    }

    fun setDefault(share: NetworkShare) = viewModelScope.launch {
        networkRepository.saveShare(share.copy(isDefault = true))
    }

    fun createShare(
        name: String, host: String, port: Int?, shareName: String,
        username: String?, password: String?, type: ShareType, isDefault: Boolean
    ) = networkRepository.createShare(name, host, port, shareName, username, password, type, isDefault)

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Results(val devices: List<fr.retrospare.blazeplayer.data.model.NetworkShare>) : ScanState()
        data class Error(val msg: String) : ScanState()
    }

    private val _scanState = kotlinx.coroutines.flow.MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: kotlinx.coroutines.flow.StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scannedShares = kotlinx.coroutines.flow.MutableStateFlow<List<fr.retrospare.blazeplayer.data.model.NetworkShare>>(emptyList())
    val scannedShares: kotlinx.coroutines.flow.StateFlow<List<fr.retrospare.blazeplayer.data.model.NetworkShare>> = _scannedShares.asStateFlow()

    private val _discoveredDevices = kotlinx.coroutines.flow.MutableStateFlow<List<NetworkScanner.DiscoveredDevice>>(emptyList())
    val discoveredDevices: kotlinx.coroutines.flow.StateFlow<List<NetworkScanner.DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isScanning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isScanning.asStateFlow()

    fun scanNetwork() {
        viewModelScope.launch {
            _isScanning.value = true
            _discoveredDevices.value = emptyList()
            val found = mutableListOf<NetworkScanner.DiscoveredDevice>()
            try {
                networkScanner.scan().collect { device ->
                    found.add(device)
                    _discoveredDevices.value = found.toList()
                }
            } catch (e: Exception) {}
            _isScanning.value = false
        }
    }

    suspend fun listShares(host: String, username: String?, password: String?) =
        networkScanner.listShares(host, username, password)

    fun scanNetwork_old() {
        // old stub
        _message.value = NetworkMessage.SCAN_UNAVAILABLE_EMULATOR
    }

    fun clearMessage() { _message.value = null }
}
