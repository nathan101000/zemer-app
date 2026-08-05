package com.jtech.zemer.ui.player

import com.jtech.zemer.playback.sonos.SonosDevice
import org.fcast.sender_sdk.DeviceInfo

/**
 * UI-facing representation of a remote playback device (FCast, Chromecast, or Sonos).
 * Decouples the UI layer from individual discovery SDK objects.
 */
sealed interface CastDeviceItem {
    val id: String
    val displayName: String

    data class FCast(val info: DeviceInfo) : CastDeviceItem {
        override val id: String get() = info.name
        override val displayName: String get() = info.name
    }

    data class Sonos(val device: SonosDevice) : CastDeviceItem {
        override val id: String get() = device.udn
        override val displayName: String get() = device.roomName
    }
}
