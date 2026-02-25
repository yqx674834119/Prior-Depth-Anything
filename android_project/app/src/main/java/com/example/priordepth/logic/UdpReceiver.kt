package com.example.priordepth.logic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpReceiver {
    private val TAG = "UdpReceiver"
    private var socket: DatagramSocket? = null
    
    // ESP32 IP is always 192.168.4.1 by default when it is a SoftAP
    private val esp32Ip = "192.168.4.1"
    private val esp32Port = 12345
    private val localPort = 12346

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _latestDepthPoint = MutableStateFlow(0f) 
    val latestDepthPoint: StateFlow<Float> = _latestDepthPoint

    suspend fun startListening() = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket(localPort)
            socket?.soTimeout = 3000 // 3 seconds timeout
            
            // 1. Send handshake to ESP32 to let it know our IP
            sendHandshake()

            val buffer = ByteArray(1024)
            var packetsReceived = 0

            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket?.receive(packet)
                    
                    if (packet.length <= 10) {
                         // This might be the "ESP32_ACK" string
                         val str = String(packet.data, 0, packet.length)
                         if (str == "ESP32_ACK") {
                             Log.d(TAG, "Connection acknowledged by ESP32")
                             _connectionStatus.value = "Connected"
                         }
                    } else if (packet.length == 64 * 4) { // 64 floats * 4 bytes
                        // We received depth data
                        packetsReceived++
                        _connectionStatus.value = "Connected ($packetsReceived frames)"
                        
                        // Parse binary float array
                        val byteBuffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                        byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // ESP32 is little endian
                        
                        // Just grab the first float as a sample to display in UI
                        val firstDepth = byteBuffer.float
                        _latestDepthPoint.value = firstDepth
                        
                        // In a real app, you would pass the whole FloatArray out to the UI
                    }
                } catch (e: SocketTimeoutException) {
                     Log.w(TAG, "Receive timeout. Re-sending handshake...")
                     _connectionStatus.value = "Reconnecting..."
                     sendHandshake()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP Error", e)
            _connectionStatus.value = "Error: ${e.message}"
        } finally {
            socket?.close()
        }
    }

    private suspend fun sendHandshake() = withContext(Dispatchers.IO) {
        try {
             val address = InetAddress.getByName(esp32Ip)
             val message = "connect".toByteArray()
             val packet = DatagramPacket(message, message.size, address, esp32Port)
             socket?.send(packet)
             Log.d(TAG, "Sent handshake to ESP32")
        } catch (e: Exception) {
             Log.e(TAG, "Failed to send handshake", e)
        }
    }

    fun stop() {
        socket?.close()
    }
}
