package com.example.offlinetranslator.mlkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal interface WifiConnectivity {
    fun isValidatedWifi(): Boolean
    fun validatedWifiChanges(): Flow<Boolean>
}

internal class AndroidWifiConnectivity(context: Context) : WifiConnectivity {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isValidatedWifi(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network).isValidatedWifi()
    }

    override fun validatedWifiChanges(): Flow<Boolean> = callbackFlow {
        fun publish() { trySend(isValidatedWifi()) }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        publish()
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun NetworkCapabilities?.isValidatedWifi(): Boolean =
        this != null &&
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
