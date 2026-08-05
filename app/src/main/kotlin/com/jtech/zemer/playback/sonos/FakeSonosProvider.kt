package com.jtech.zemer.playback.sonos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mock provider emitting fake Sonos devices for UI testing and layout verification
 * without requiring physical Sonos hardware on the local network.
 */
class FakeSonosProvider(
    initialDevices: List<SonosDevice> = listOf(
        SonosDevice(
            udn = "uuid:RINCON_FAKE_KITCHEN",
            roomName = "Kitchen Sonos",
            modelName = "Sonos One",
            ipAddress = "192.168.1.101",
            port = 1400,
            locationUrl = "http://192.168.1.101:1400/xml/device_description.xml",
        ),
        SonosDevice(
            udn = "uuid:RINCON_FAKE_LIVING_ROOM",
            roomName = "Living Room Sonos",
            modelName = "Sonos Beam",
            ipAddress = "192.168.1.102",
            port = 1400,
            locationUrl = "http://192.168.1.102:1400/xml/device_description.xml",
        ),
    ),
) {
    private val _discoveredDevices = MutableStateFlow(initialDevices)
    val discoveredDevices: StateFlow<List<SonosDevice>> = _discoveredDevices.asStateFlow()

    fun updateDevices(devices: List<SonosDevice>) {
        _discoveredDevices.value = devices
    }
}
