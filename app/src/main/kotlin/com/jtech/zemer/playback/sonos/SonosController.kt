package com.jtech.zemer.playback.sonos

import com.jtech.zemer.models.MediaMetadata
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controls active playback on a connected [SonosDevice] via UPnP AVTransport and RenderingControl
 * SOAP commands.
 *
 * State is kept authoritative by a ~1 Hz polling loop ([startPolling]) that reads
 * [SonosSoapClient.getPositionInfo] and [SonosSoapClient.getIsPlaying] from the speaker so the
 * UI seek bar and play/pause icon track reality rather than the last-issued command.
 *
 * Volume is a separate [SonosSoapClient.RenderingControl] service; [stepVolume] maps the
 * +1/−1 hardware-key direction to a 5-point step on the 0–100 Sonos scale.
 */
class SonosController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    companion object {
        /** Volume step size matching the FCast receiver's single-step size. */
        const val VOLUME_STEP = 5

        /** Polling interval for GetPositionInfo / GetTransportInfo. */
        private const val POLL_INTERVAL_MS = 1_000L
    }

    // ── Connection ────────────────────────────────────────────────────────────
    private val _connectedDevice = MutableStateFlow<SonosDevice?>(null)
    val connectedDevice: StateFlow<SonosDevice?> = _connectedDevice.asStateFlow()

    // ── Playback state (authoritative from polling after the first poll cycle) ─
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTimeSec = MutableStateFlow(0.0)
    val currentTimeSec: StateFlow<Double> = _currentTimeSec.asStateFlow()

    private val _durationSec = MutableStateFlow(0.0)
    val durationSec: StateFlow<Double> = _durationSec.asStateFlow()

    // ── Volume (0–100, kept in sync with the speaker) ─────────────────────────
    private val _volumeLevel = MutableStateFlow<Int?>(null)
    /** Last-known Sonos volume (0–100), or null before the first poll succeeds. */
    val volumeLevel: StateFlow<Int?> = _volumeLevel.asStateFlow()

    private var pollJob: Job? = null

    // ── Device lifecycle ──────────────────────────────────────────────────────

    fun setTargetDevice(device: SonosDevice?) {
        _connectedDevice.value = device
        if (device == null) {
            stopPolling()
            _isPlaying.value = false
            _currentTimeSec.value = 0.0
            _durationSec.value = 0.0
            _volumeLevel.value = null
        }
    }

    // ── AVTransport commands ──────────────────────────────────────────────────

    /**
     * Sets the stream URI on the speaker (with DIDL-Lite metadata), then immediately issues Play.
     * [resumePosSec] is noted optimistically; the polling loop will correct it once the speaker
     * reports its own position.
     *
     * [mimeType] should come from MusicService.streamContentType(mediaId) so the DIDL-Lite
     * protocolInfo matches the actual container format.
     */
    fun load(
        streamUrl: String,
        metadata: MediaMetadata?,
        resumePosSec: Double = 0.0,
        mimeType: String = "audio/mp4",
    ) {
        val device = _connectedDevice.value ?: return
        val title = metadata?.title ?: "Audio Stream"
        val artist = metadata?.artists?.joinToString(", ") { it.name } ?: ""

        scope.launch {
            Timber.d("SonosController: loading stream on %s — %s", device.roomName, streamUrl)
            val setUriBody = SonosSoapClient.buildSetUriBody(streamUrl, title, artist, mimeType)
            val uriSuccess = SonosSoapClient.sendSoapRequest(device.ipAddress, device.port, "SetAVTransportURI", setUriBody)
            if (uriSuccess) {
                _currentTimeSec.value = resumePosSec
                play()
            } else {
                Timber.w("SonosController: SetAVTransportURI failed on %s", device.roomName)
            }
        }
    }

    fun play() {
        val device = _connectedDevice.value ?: return
        scope.launch {
            val success = SonosSoapClient.sendSoapRequest(device.ipAddress, device.port, "Play", SonosSoapClient.buildPlayBody())
            if (success) {
                _isPlaying.value = true
                startPolling()
            }
        }
    }

    fun pause() {
        val device = _connectedDevice.value ?: return
        scope.launch {
            val success = SonosSoapClient.sendSoapRequest(device.ipAddress, device.port, "Pause", SonosSoapClient.buildPauseBody())
            if (success) {
                _isPlaying.value = false
                // Keep polling so the UI continues to reflect position while paused.
            }
        }
    }

    fun stop() {
        val device = _connectedDevice.value ?: return
        scope.launch {
            SonosSoapClient.sendSoapRequest(device.ipAddress, device.port, "Stop", SonosSoapClient.buildStopBody())
            _isPlaying.value = false
            stopPolling()
        }
    }

    fun seek(positionSec: Double) {
        val device = _connectedDevice.value ?: return
        scope.launch {
            val success = SonosSoapClient.sendSoapRequest(device.ipAddress, device.port, "Seek", SonosSoapClient.buildSeekBody(positionSec))
            if (success) {
                // Optimistic update; polling will correct quickly.
                _currentTimeSec.value = positionSec
            }
        }
    }

    // ── RenderingControl — volume ─────────────────────────────────────────────

    /**
     * Steps the Sonos volume by [direction] (+1 or −1) using a [VOLUME_STEP]-point increment,
     * clamped to the 0–100 range. Called by the hardware volume-key handler.
     */
    fun stepVolume(direction: Int) {
        val device = _connectedDevice.value ?: return
        scope.launch {
            // Use the last-polled level as the base, or fetch it fresh if unknown.
            val current = _volumeLevel.value
                ?: SonosSoapClient.getVolume(device.ipAddress, device.port)
                ?: return@launch
            val next = (current + direction * VOLUME_STEP).coerceIn(0, 100)
            val success = SonosSoapClient.setVolume(device.ipAddress, device.port, next)
            if (success) {
                _volumeLevel.value = next
                Timber.d("SonosController: volume %d → %d on %s", current, next, device.roomName)
            }
        }
    }

    /**
     * Sets the Sonos volume to an absolute value (0–100), used by a UI volume slider.
     */
    fun setVolume(volume: Int) {
        val device = _connectedDevice.value ?: return
        scope.launch {
            val success = SonosSoapClient.setVolume(device.ipAddress, device.port, volume)
            if (success) _volumeLevel.value = volume.coerceIn(0, 100)
        }
    }

    // ── State polling loop ────────────────────────────────────────────────────

    /**
     * Starts (or restarts) the ~1 Hz polling loop that keeps [currentTimeSec], [durationSec],
     * [isPlaying], and [volumeLevel] authoritative from the speaker's own SOAP responses.
     * Idempotent: a second call while a loop is already running is a no-op.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var volumePollCounter = 0
            while (isActive) {
                val device = _connectedDevice.value ?: break
                // Position + duration
                SonosSoapClient.getPositionInfo(device.ipAddress, device.port)?.let { info ->
                    _currentTimeSec.value = info.positionSec
                    if (info.durationSec > 0.0) _durationSec.value = info.durationSec
                }
                // Transport state (PLAYING / PAUSED_PLAYBACK / STOPPED)
                SonosSoapClient.getIsPlaying(device.ipAddress, device.port)?.let { playing ->
                    _isPlaying.value = playing
                }
                // Volume — poll every ~10 s (every 10th tick) to reduce traffic
                if (volumePollCounter == 0) {
                    SonosSoapClient.getVolume(device.ipAddress, device.port)?.let { vol ->
                        _volumeLevel.value = vol
                    }
                }
                volumePollCounter = (volumePollCounter + 1) % 10
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
