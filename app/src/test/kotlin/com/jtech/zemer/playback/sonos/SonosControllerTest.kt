package com.jtech.zemer.playback.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SonosController logic and related helpers that are pure / JVM-testable:
 *
 * - REL_TIME string ↔ seconds round-trips (the path GetPositionInfo feeds into currentTimeSec /
 *   durationSec and that Seek uses to target a position).
 * - Volume step arithmetic (stepVolume / setVolume) including boundary clamping.
 * - Initial state of a freshly constructed controller (nothing connected, flows at zero).
 *
 * The polling loop and actual SOAP dispatch require network and are not unit-tested here;
 * those are covered by the empirical tests/web-remix-stream.mjs harness on a live LAN.
 */
class SonosControllerTest {

    // ── REL_TIME parsing ──────────────────────────────────────────────────────
    // SonosController reads positions via SonosSoapClient.relTimeToSeconds and converts back
    // via secondsToRelTime for Seek commands — these tests pin the round-trip.

    @Test
    fun relTimeRoundTrip_zero() {
        val secs = SonosSoapClient.relTimeToSeconds("00:00:00")
        assertEquals(0.0, secs, 0.001)
        assertEquals("00:00:00", SonosSoapClient.secondsToRelTime(secs))
    }

    @Test
    fun relTimeRoundTrip_minutesAndSeconds() {
        val secs = SonosSoapClient.relTimeToSeconds("00:03:45")
        assertEquals(225.0, secs, 0.001)
        assertEquals("00:03:45", SonosSoapClient.secondsToRelTime(secs))
    }

    @Test
    fun relTimeRoundTrip_withHours() {
        val secs = SonosSoapClient.relTimeToSeconds("01:15:30")
        assertEquals(4530.0, secs, 0.001)
        assertEquals("01:15:30", SonosSoapClient.secondsToRelTime(secs))
    }

    @Test
    fun relTimeToSeconds_singleDigitHour() {
        // Some Sonos firmwares emit "0:03:45" — must parse identically to "00:03:45"
        assertEquals(225.0, SonosSoapClient.relTimeToSeconds("0:03:45"), 0.001)
    }

    @Test
    fun relTimeToSeconds_invalidFormat_returnsZero() {
        assertEquals(0.0, SonosSoapClient.relTimeToSeconds("NOT_IMPLEMENTED"), 0.001)
        assertEquals(0.0, SonosSoapClient.relTimeToSeconds(""), 0.001)
        assertEquals(0.0, SonosSoapClient.relTimeToSeconds("12:34"), 0.001) // only two parts
    }

    @Test
    fun secondsToRelTime_negativeClampsToZero() {
        // SonosController may pass a negative resumePosSec on edge cases; must not produce "-"
        assertEquals("00:00:00", SonosSoapClient.secondsToRelTime(-5.0))
    }

    @Test
    fun secondsToRelTime_largeValue() {
        // 2 h 30 m 00 s = 9000 s
        assertEquals("02:30:00", SonosSoapClient.secondsToRelTime(9000.0))
    }

    // ── Volume step arithmetic ────────────────────────────────────────────────
    // stepVolume(direction) applies VOLUME_STEP in the given direction and clamps to 0–100.
    // We test the math directly since the SOAP call needs a real network.

    @Test
    fun volumeStep_upFromMid() {
        val base = 50
        val next = (base + SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(55, next)
    }

    @Test
    fun volumeStep_downFromMid() {
        val base = 50
        val next = (base - SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(45, next)
    }

    @Test
    fun volumeStep_clampAtMax() {
        val base = 98
        val next = (base + SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(100, next)
    }

    @Test
    fun volumeStep_clampAtMin() {
        val base = 2
        val next = (base - SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(0, next)
    }

    @Test
    fun volumeStep_alreadyAtMax_staysAt100() {
        val base = 100
        val next = (base + SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(100, next)
    }

    @Test
    fun volumeStep_alreadyAtMin_staysAt0() {
        val base = 0
        val next = (base - SonosController.VOLUME_STEP).coerceIn(0, 100)
        assertEquals(0, next)
    }

    // ── setVolume clamp (mirrors SonosSoapClient.buildSetVolumeBody clamp) ────

    @Test
    fun setVolume_clampsBelowZero() {
        val body = SonosSoapClient.buildSetVolumeBody(-1)
        assertTrue(body.contains("<DesiredVolume>0</DesiredVolume>"))
    }

    @Test
    fun setVolume_clampsAbove100() {
        val body = SonosSoapClient.buildSetVolumeBody(101)
        assertTrue(body.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test
    fun setVolume_midRangePassedThrough() {
        val body = SonosSoapClient.buildSetVolumeBody(60)
        assertTrue(body.contains("<DesiredVolume>60</DesiredVolume>"))
    }

    // ── Initial controller state ──────────────────────────────────────────────

    @Test
    fun freshController_hasNoConnectedDevice() {
        val controller = SonosController()
        assertNull(controller.connectedDevice.value)
    }

    @Test
    fun freshController_isNotPlaying() {
        val controller = SonosController()
        assertFalse(controller.isPlaying.value)
    }

    @Test
    fun freshController_positionAndDurationAreZero() {
        val controller = SonosController()
        assertEquals(0.0, controller.currentTimeSec.value, 0.001)
        assertEquals(0.0, controller.durationSec.value, 0.001)
    }

    @Test
    fun freshController_volumeIsNull() {
        val controller = SonosController()
        assertNull(controller.volumeLevel.value)
    }

    @Test
    fun setTargetDevice_null_resetsAllState() {
        val controller = SonosController()
        val device = SonosDevice(
            udn = "uuid:RINCON_TEST",
            roomName = "Test Room",
            modelName = "Sonos One",
            ipAddress = "192.168.1.99",
            port = 1400,
            locationUrl = "http://192.168.1.99:1400/xml/device_description.xml",
        )
        controller.setTargetDevice(device)
        assertEquals(device, controller.connectedDevice.value)

        controller.setTargetDevice(null)
        assertNull(controller.connectedDevice.value)
        assertFalse(controller.isPlaying.value)
        assertEquals(0.0, controller.currentTimeSec.value, 0.001)
        assertEquals(0.0, controller.durationSec.value, 0.001)
        assertNull(controller.volumeLevel.value)
    }
}
