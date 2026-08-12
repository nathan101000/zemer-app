package com.jtech.zemer.playback.sonos

import com.jtech.zemer.models.MediaMetadata
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SonosConnectResult {
    data object Connected : SonosConnectResult
    data class Failed(val deviceName: String) : SonosConnectResult
}

/**
 * Manages connecting to a [SonosDevice] and orchestrating playback session startup.
 *
 * Stream resolution and relay-URL acquisition happen in the caller (CastBottomSheet, on the
 * service scope) following the same pattern as [com.jtech.zemer.playback.CastConnector], so this
 * class stays free of MusicService dependencies and is independently testable.
 *
 * The [controller] is public so callers can route hardware volume keys to [SonosController.stepVolume]
 * while a Sonos session is active.
 */
class SonosConnector(
    val controller: SonosController = SonosController(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val _connectedDevice = MutableStateFlow<SonosDevice?>(null)
    val connectedDevice: StateFlow<SonosDevice?> = _connectedDevice.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * Connects to [device] and starts playback of [streamUrl].
     *
     * [streamUrl] should be the relay URL obtained via MusicService.relayedStreamUrl — the caller
     * is responsible for setting castStreamRelay.receiverAddress before calling this.
     * [mimeType] should come from MusicService.streamContentType(mediaId) for correct DIDL-Lite
     * protocolInfo — defaults to "audio/mp4" as a safe fallback.
     * [resumePosSec] is the local player's current position in seconds, used for resume-point
     * playback.
     *
     * [onSuccess] / [onFailure] are called on the coroutine dispatcher after the load is issued
     * (before SOAP round-trips complete). They are intended for UI spinner teardown.
     */
    fun connect(
        device: SonosDevice,
        streamUrl: String?,
        metadata: MediaMetadata?,
        mimeType: String = "audio/mp4",
        resumePosSec: Double = 0.0,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        scope.launch {
            Timber.d("SonosConnector: connecting to %s (%s)", device.roomName, device.ipAddress)
            _connectedDevice.value = device
            controller.setTargetDevice(device)
            _isConnected.value = true

            if (!streamUrl.isNullOrEmpty()) {
                controller.load(streamUrl, metadata, resumePosSec, mimeType)
            }
            onSuccess()
        }
    }

    fun disconnect() {
        controller.stop()
        controller.setTargetDevice(null)
        _connectedDevice.value = null
        _isConnected.value = false
        Timber.d("SonosConnector: disconnected")
    }
}
