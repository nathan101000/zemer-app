package com.jtech.zemer.playback.sonos

import android.content.Context
import android.net.wifi.WifiManager
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles SSDP UDP multicast discovery and UPnP XML description parsing for Sonos speakers.
 */
class SonosSsdpDiscoverer(private val context: Context? = null) {

    companion object {
        const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val ST_ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"

        const val SSDP_SEARCH_MSG =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $ST_ZONE_PLAYER\r\n" +
                "\r\n"

        private val ROOM_NAME_PATTERN = Pattern.compile("<roomName>(.*?)</roomName>", Pattern.CASE_INSENSITIVE)
        private val FRIENDLY_NAME_PATTERN = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.CASE_INSENSITIVE)
        private val MODEL_NAME_PATTERN = Pattern.compile("<modelName>(.*?)</modelName>", Pattern.CASE_INSENSITIVE)
        private val UDN_PATTERN = Pattern.compile("<UDN>(.*?)</UDN>", Pattern.CASE_INSENSITIVE)

        /**
         * Parses SSDP response HTTP headers into a case-insensitive header map.
         */
        fun parseSsdpHeaders(response: String): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            val lines = response.split("\r\n", "\n")
            for (line in lines) {
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().uppercase(Locale.ROOT)
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }
            return headers
        }

        /**
         * Parses UPnP device_description.xml content into a [SonosDevice].
         */
        fun parseDeviceDescriptionXml(
            xml: String,
            locationUrl: String,
            fallbackIp: String,
            fallbackPort: Int = 1400,
        ): SonosDevice? {
            val roomNameMatcher = ROOM_NAME_PATTERN.matcher(xml)
            val friendlyNameMatcher = FRIENDLY_NAME_PATTERN.matcher(xml)
            val modelNameMatcher = MODEL_NAME_PATTERN.matcher(xml)
            val udnMatcher = UDN_PATTERN.matcher(xml)

            val roomName = if (roomNameMatcher.find()) roomNameMatcher.group(1)?.trim() else null
            val friendlyName = if (friendlyNameMatcher.find()) friendlyNameMatcher.group(1)?.trim() else null
            val displayName = roomName?.takeIf { it.isNotBlank() }
                ?: friendlyName?.takeIf { it.isNotBlank() }
                ?: "Sonos Speaker"

            val modelName = if (modelNameMatcher.find()) modelNameMatcher.group(1)?.trim() ?: "Sonos" else "Sonos"
            val udn = if (udnMatcher.find()) udnMatcher.group(1)?.trim() ?: locationUrl else locationUrl

            return SonosDevice(
                udn = udn,
                roomName = displayName,
                modelName = modelName,
                ipAddress = fallbackIp,
                port = fallbackPort,
                locationUrl = locationUrl,
            )
        }
    }

    /**
     * Executes a single SSDP multicast discovery scan for Sonos devices on the local network.
     */
    suspend fun discoverDevices(timeoutMs: Int = 3000): List<SonosDevice> = withContext(Dispatchers.IO) {
        val discoveredLocations = mutableSetOf<String>()
        val devices = mutableListOf<SonosDevice>()

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            context?.let { ctx ->
                val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("ZemerSonosMulticastLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to acquire WifiManager MulticastLock")
        }

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val sendData = SSDP_SEARCH_MSG.toByteArray(StandardCharsets.UTF_8)
                val group = InetAddress.getByName(SSDP_MULTICAST_ADDRESS)
                val packet = DatagramPacket(sendData, sendData.size, group, SSDP_PORT)

                socket.send(packet)

                val receiveData = ByteArray(2048)
                val endTime = System.currentTimeMillis() + timeoutMs

                while (System.currentTimeMillis() < endTime) {
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, StandardCharsets.UTF_8)
                        val headers = parseSsdpHeaders(response)
                        val location = headers["LOCATION"] ?: continue

                        if (discoveredLocations.add(location)) {
                            val ip = receivePacket.address.hostAddress ?: ""
                            val port = URI(location).port.takeIf { it > 0 } ?: 1400
                            fetchAndParseDevice(location, ip, port)?.let { devices.add(it) }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Timber.d(e, "Error receiving SSDP packet")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during SSDP discovery scan")
        } finally {
            try {
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (e: Exception) {
                Timber.w(e, "Error releasing MulticastLock")
            }
        }

        devices
    }

    private fun fetchAndParseDevice(locationUrl: String, fallbackIp: String, port: Int): SonosDevice? {
        return runCatching {
            val url = URL(locationUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }
            if (connection.responseCode == 200) {
                val xml = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                parseDeviceDescriptionXml(xml, locationUrl, fallbackIp, port)
            } else null
        }.getOrNull()
    }
}
