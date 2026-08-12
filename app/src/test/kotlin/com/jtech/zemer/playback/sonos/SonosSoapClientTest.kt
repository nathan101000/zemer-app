package com.jtech.zemer.playback.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosSoapClientTest {

    @Test
    fun testXmlEscaping() {
        val raw = "Tom & Jerry <Live> \"Concert\" '2026'"
        val escaped = SonosSoapClient.escapeXml(raw)
        assertEquals("Tom &amp; Jerry &lt;Live&gt; &quot;Concert&quot; &apos;2026&apos;", escaped)
    }

    @Test
    fun testSecondsToRelTimeConversion() {
        assertEquals("00:00:00", SonosSoapClient.secondsToRelTime(0.0))
        assertEquals("00:03:45", SonosSoapClient.secondsToRelTime(225.0))
        assertEquals("01:15:30", SonosSoapClient.secondsToRelTime(4530.0))
    }

    @Test
    fun testRelTimeToSecondsConversion() {
        assertEquals(0.0, SonosSoapClient.relTimeToSeconds("00:00:00"), 0.01)
        assertEquals(225.0, SonosSoapClient.relTimeToSeconds("00:03:45"), 0.01)
        assertEquals(4530.0, SonosSoapClient.relTimeToSeconds("01:15:30"), 0.01)
    }

    @Test
    fun testBuildSetUriBodyContainsDidlLiteAndEscapedUrl() {
        val streamUrl = "http://192.168.1.50:8080/stream/abc?param=1&foo=bar"
        val body = SonosSoapClient.buildSetUriBody(streamUrl, "Test Song", "Test Artist")

        assertTrue(body.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("&lt;dc:title&gt;Test Song&lt;/dc:title&gt;"))
        assertTrue(body.contains("http://192.168.1.50:8080/stream/abc?param=1&amp;foo=bar"))
    }

    @Test
    fun testBuildSetUriBodyPassesMimeTypeToDidlLite() {
        val body = SonosSoapClient.buildSetUriBody(
            streamUrl = "http://192.168.1.1:8080/stream/tok",
            title = "Track",
            artist = "Artist",
            mimeType = "audio/webm",
        )
        // protocolInfo in the DIDL-Lite res element must carry the caller's mimeType
        assertTrue(body.contains("audio/webm"))
        // default "audio/mp4" must NOT appear when overridden
        assertFalse(body.contains("audio/mp4"))
    }

    @Test
    fun testBuildPlayPauseStopSeekBodies() {
        val play = SonosSoapClient.buildPlayBody()
        assertTrue(play.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(play.contains("<Speed>1</Speed>"))

        val pause = SonosSoapClient.buildPauseBody()
        assertTrue(pause.contains("<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))

        val stop = SonosSoapClient.buildStopBody()
        assertTrue(stop.contains("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))

        val seek = SonosSoapClient.buildSeekBody(150.0)
        assertTrue(seek.contains("<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(seek.contains("<Target>00:02:30</Target>"))
    }

    // ── GetPositionInfo / GetTransportInfo bodies ─────────────────────────────

    @Test
    fun testBuildGetPositionInfoBody() {
        val body = SonosSoapClient.buildGetPositionInfoBody()
        assertTrue(body.contains("<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
    }

    @Test
    fun testBuildGetTransportInfoBody() {
        val body = SonosSoapClient.buildGetTransportInfoBody()
        assertTrue(body.contains("<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
    }

    // ── RenderingControl volume bodies ────────────────────────────────────────

    @Test
    fun testBuildSetVolumeBody() {
        val body = SonosSoapClient.buildSetVolumeBody(75)
        assertTrue(body.contains("<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">"))
        assertTrue(body.contains("<Channel>Master</Channel>"))
        assertTrue(body.contains("<DesiredVolume>75</DesiredVolume>"))
    }

    @Test
    fun testBuildSetVolumeBodyClampsBelowZero() {
        val body = SonosSoapClient.buildSetVolumeBody(-10)
        assertTrue(body.contains("<DesiredVolume>0</DesiredVolume>"))
    }

    @Test
    fun testBuildSetVolumeBodyClampsAbove100() {
        val body = SonosSoapClient.buildSetVolumeBody(120)
        assertTrue(body.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test
    fun testBuildGetVolumeBody() {
        val body = SonosSoapClient.buildGetVolumeBody()
        assertTrue(body.contains("<u:GetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">"))
        assertTrue(body.contains("<Channel>Master</Channel>"))
    }

    // ── XML response parsers ──────────────────────────────────────────────────

    @Test
    fun testParsePositionInfoReturnsCorrectValues() {
        val xml = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <Track>1</Track>
                  <TrackDuration>00:04:05</TrackDuration>
                  <TrackURI>http://192.168.1.1:8080/stream/tok</TrackURI>
                  <RelTime>00:01:30</RelTime>
                  <AbsTime>NOT_IMPLEMENTED</AbsTime>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val info = SonosSoapClient.parsePositionInfo(xml)
        assertNotNull(info)
        assertEquals(90.0, info!!.positionSec, 0.01)   // 00:01:30
        assertEquals(245.0, info.durationSec, 0.01)    // 00:04:05
    }

    @Test
    fun testParsePositionInfoReturnNullOnMissingFields() {
        // No RelTime or TrackDuration tags
        val xml = "<s:Envelope><s:Body><u:GetPositionInfoResponse/></s:Body></s:Envelope>"
        assertNull(SonosSoapClient.parsePositionInfo(xml))
    }

    @Test
    fun testParseIsPlayingReturnsTrueForPlaying() {
        val xml = """
            <s:Envelope><s:Body>
              <u:GetTransportInfoResponse>
                <CurrentTransportState>PLAYING</CurrentTransportState>
              </u:GetTransportInfoResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(true, SonosSoapClient.parseIsPlaying(xml))
    }

    @Test
    fun testParseIsPlayingReturnsFalseForPaused() {
        val xml = """
            <s:Envelope><s:Body>
              <u:GetTransportInfoResponse>
                <CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
              </u:GetTransportInfoResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(false, SonosSoapClient.parseIsPlaying(xml))
    }

    @Test
    fun testParseIsPlayingReturnsFalseForStopped() {
        val xml = """
            <s:Envelope><s:Body>
              <u:GetTransportInfoResponse>
                <CurrentTransportState>STOPPED</CurrentTransportState>
              </u:GetTransportInfoResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(false, SonosSoapClient.parseIsPlaying(xml))
    }

    @Test
    fun testParseIsPlayingReturnsNullOnMissingState() {
        val xml = "<s:Envelope><s:Body><u:GetTransportInfoResponse/></s:Body></s:Envelope>"
        assertNull(SonosSoapClient.parseIsPlaying(xml))
    }

    @Test
    fun testParseVolumeReturnsCorrectLevel() {
        val xml = """
            <s:Envelope><s:Body>
              <u:GetVolumeResponse>
                <CurrentVolume>42</CurrentVolume>
              </u:GetVolumeResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(42, SonosSoapClient.parseVolume(xml))
    }

    @Test
    fun testParseVolumeReturnsNullOnMissingTag() {
        val xml = "<s:Envelope><s:Body><u:GetVolumeResponse/></s:Body></s:Envelope>"
        assertNull(SonosSoapClient.parseVolume(xml))
    }

    @Test
    fun testRelTimeToSecondsHandlesSingleDigitHour() {
        // Some Sonos firmwares emit "0:03:45" instead of "00:03:45"
        assertEquals(225.0, SonosSoapClient.relTimeToSeconds("0:03:45"), 0.01)
    }
}
