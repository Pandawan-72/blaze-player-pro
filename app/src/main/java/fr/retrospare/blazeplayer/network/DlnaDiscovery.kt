package fr.retrospare.blazeplayer.network

import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DlnaDiscovery @Inject constructor() {

    // DLNA/UPnP discovery via SSDP multicast (239.255.255.250:1900)
    fun discoverDevices(): Flow<NetworkShare> = flow {
        // TODO: Implement UPnP/SSDP discovery
        // Envoi d'un M-SEARCH sur le multicast SSDP
        // Parse les réponses LOCATION header pour trouver les media servers
    }

    fun stopDiscovery() {
        // TODO: Arrêter la découverte
    }
}
