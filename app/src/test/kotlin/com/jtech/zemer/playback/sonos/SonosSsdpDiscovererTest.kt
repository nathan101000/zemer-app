package com.jtech.zemer.playback.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SonosSsdpDiscovererTest {

    @Test
    fun testParseSsdpHeadersCaseInsensitive() {
        val sampleResponse =
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age = 1800\r\n" +
                "Location: http://192.168.1.50:1400/xml/device_description.xml\r\n" +
                "Server: Linux UPnP/1.0 Sonos/79.1-53290 (ZPS12)\r\n" +
                "st: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
                "USN: uuid:RINCON_000E5812345601400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

        val headers = SonosSsdpDiscoverer.parseSsdpHeaders(sampleResponse)

        assertEquals("http://192.168.1.50:1400/xml/device_description.xml", headers["LOCATION"])
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", headers["ST"])
        assertEquals("uuid:RINCON_000E5812345601400::urn:schemas-upnp-org:device:ZonePlayer:1", headers["USN"])
    }

    @Test
    fun testParseDeviceDescriptionXmlWithRoomName() {
        val sampleXml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
                    <friendlyName>192.168.1.50 - Sonos One</friendlyName>
                    <roomName>Kitchen</roomName>
                    <modelName>Sonos One</modelName>
                    <UDN>uuid:RINCON_000E5812345601400</UDN>
                </device>
            </root>
            """.trimIndent()

        val device = SonosSsdpDiscoverer.parseDeviceDescriptionXml(
            xml = sampleXml,
            locationUrl = "http://192.168.1.50:1400/xml/device_description.xml",
            fallbackIp = "192.168.1.50",
            fallbackPort = 1400,
        )

        assertNotNull(device)
        assertEquals("Kitchen", device?.roomName)
        assertEquals("Sonos One", device?.modelName)
        assertEquals("uuid:RINCON_000E5812345601400", device?.udn)
        assertEquals("192.168.1.50", device?.ipAddress)
        assertEquals(1400, device?.port)
    }

    @Test
    fun testParseDeviceDescriptionXmlFallbackToFriendlyName() {
        val sampleXml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <device>
                    <friendlyName>Sonos Move</friendlyName>
                    <modelName>Sonos Move</modelName>
                    <UDN>uuid:RINCON_99999999999901400</UDN>
                </device>
            </root>
            """.trimIndent()

        val device = SonosSsdpDiscoverer.parseDeviceDescriptionXml(
            xml = sampleXml,
            locationUrl = "http://192.168.1.99:1400/xml/device_description.xml",
            fallbackIp = "192.168.1.99",
            fallbackPort = 1400,
        )

        assertNotNull(device)
        assertEquals("Sonos Move", device?.roomName)
        assertEquals("Sonos Move", device?.modelName)
    }
}
