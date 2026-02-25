package com.example.priordepth.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UdpViewModel : ViewModel() {
    private val udpReceiver = UdpReceiver()

    val connectionStatus: StateFlow<String> = udpReceiver.connectionStatus
    val latestDepthPoint: StateFlow<Float> = udpReceiver.latestDepthPoint

    fun startConnection() {
        viewModelScope.launch {
            udpReceiver.startListening()
        }
    }

    fun stopConnection() {
        udpReceiver.stop()
    }

    override fun onCleared() {
        super.onCleared()
        udpReceiver.stop()
    }
}
