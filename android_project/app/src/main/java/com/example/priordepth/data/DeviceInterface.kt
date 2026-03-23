package com.example.priordepth.data

object DeviceInterface {
    const val softApSsid = "ToFSense_AP"
    const val defaultHost = "192.168.4.1"

    const val tofTcpPort = 8080
    const val cameraHttpPort = 81
    const val cameraPath = "/"

    const val tofJsonKey = "d"

    val cameraUrl: String
        get() = "http://$defaultHost:$cameraHttpPort$cameraPath"
}
