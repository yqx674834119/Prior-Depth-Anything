package com.example.priordepth.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class TofRepository {
    private val _depthData = MutableStateFlow<IntArray>(IntArray(64) { 0 })
    val depthData: StateFlow<IntArray> = _depthData

    private var tcpJob: Job? = null
    private var socket: Socket? = null

    fun connect(
        ip: String = DeviceInterface.defaultHost,
        port: Int = DeviceInterface.tofTcpPort
    ) {
        if (tcpJob?.isActive == true) return
        
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("TofRepository", "Connecting to $ip:$port...")
                socket = Socket(ip, port)
                val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                Log.d("TofRepository", "Connected. Waiting for data...")
                
                while (isActive) {
                    val line = reader.readLine()
                    if (line != null && line.startsWith("{\"${DeviceInterface.tofJsonKey}\":[")) {
                        try {
                            val jsonObject = JSONObject(line)
                            val jsonArray = jsonObject.getJSONArray(DeviceInterface.tofJsonKey)
                            val newArray = IntArray(64)
                            for (i in 0 until 64) {
                                newArray[i] = jsonArray.getInt(i)
                            }
                            _depthData.value = newArray
                        } catch (e: Exception) {
                            Log.e("TofRepository", "JSON Parse error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TofRepository", "TCP error: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    fun disconnect() {
        tcpJob?.cancel()
        tcpJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
    }
}
