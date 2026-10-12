package com.example.data.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkConnectivityMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(isCurrentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isWifi = MutableStateFlow(isCurrentlyWifi())
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    var onNetworkLostCallback: (() -> Unit)? = null
    var onNetworkAvailableCallback: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isWifiNet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            _isConnected.value = hasInternet
            _isWifi.value = isWifiNet

            if (hasInternet) {
                onNetworkAvailableCallback?.invoke()
            }
        }

        override fun onLost(network: Network) {
            _isConnected.value = false
            _isWifi.value = false
            onNetworkLostCallback?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifiNet = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            _isConnected.value = hasInternet
            _isWifi.value = isWifiNet
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isCurrentlyConnected(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun isCurrentlyWifi(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private var isRegistered = true

    fun unregister() {
        if (isRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isRegistered = false
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
        }
    }
}
