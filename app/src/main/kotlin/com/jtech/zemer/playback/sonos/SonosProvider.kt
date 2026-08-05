package com.jtech.zemer.playback.sonos

import android.content.Context
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages background SSDP discovery scans and exposes discovered Sonos devices reactively.
 */
class SonosProvider(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val discoverer: SonosSsdpDiscoverer = SonosSsdpDiscoverer(context),
) {
    private val _discoveredDevices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<SonosDevice>> = _discoveredDevices.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var scanJob: Job? = null

    fun startDiscovery() {
        refresh()
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        _isSearching.value = false
    }

    fun refresh() {
        if (_isSearching.value) return
        scanJob = scope.launch {
            _isSearching.value = true
            try {
                Timber.d("Starting Sonos SSDP scan...")
                val found = discoverer.discoverDevices()
                _discoveredDevices.value = found
                Timber.d("Sonos scan completed, found ${found.size} speakers: ${found.map { it.roomName }}")
            } catch (e: Exception) {
                Timber.e(e, "Error during Sonos discovery")
            } finally {
                _isSearching.value = false
            }
        }
    }
}
