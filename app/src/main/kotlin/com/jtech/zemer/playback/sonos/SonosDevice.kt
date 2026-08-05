package com.jtech.zemer.playback.sonos

/**
 * Domain model representing a Sonos speaker discovered via SSDP / UPnP.
 */
data class SonosDevice(
    val udn: String,
    val roomName: String,
    val modelName: String,
    val ipAddress: String,
    val port: Int = 1400,
    val locationUrl: String,
)
