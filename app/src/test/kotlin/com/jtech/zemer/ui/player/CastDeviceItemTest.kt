package com.jtech.zemer.ui.player

import com.jtech.zemer.playback.sonos.SonosDevice
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Test

class CastDeviceItemTest {

    @Test
    fun testCastDeviceItemFCastMapping() {
        val info = DeviceInfo(
            name = "Living Room TV",
            protocol = ProtocolType.F_CAST,
            addresses = emptyList(),
            port = 46899.toUShort(),
        )
        val item = CastDeviceItem.FCast(info)

        assertEquals("Living Room TV", item.id)
        assertEquals("Living Room TV", item.displayName)
    }

    @Test
    fun testCastDeviceItemSonosMapping() {
        val sonos = SonosDevice(
            udn = "uuid:RINCON_12345",
            roomName = "Kitchen",
            modelName = "Sonos One",
            ipAddress = "192.168.1.100",
            port = 1400,
            locationUrl = "http://192.168.1.100:1400/xml/device_description.xml",
        )
        val item = CastDeviceItem.Sonos(sonos)

        assertEquals("uuid:RINCON_12345", item.id)
        assertEquals("Kitchen", item.displayName)
    }
}
