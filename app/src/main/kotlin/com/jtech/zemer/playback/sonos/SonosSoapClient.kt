package com.jtech.zemer.playback.sonos

import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight UPnP SOAP HTTP client for sending AVTransport commands to Sonos speakers.
 */
object SonosSoapClient {

    private const val AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control"
    private const val AV_TRANSPORT_NS = "urn:schemas-upnp-org:service:AVTransport:1"

    private const val RENDERING_CONTROL_PATH = "/MediaRenderer/RenderingControl/Control"
    private const val RENDERING_CONTROL_NS = "urn:schemas-upnp-org:service:RenderingControl:1"

    private const val USER_AGENT = "Zemer-Android/1.0 UPnP/1.0"

    /**
     * XML escapes special characters for embedding XML inside SOAP payload tags.
     */
    fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Converts seconds (`Double`) to UPnP REL_TIME format (`HH:MM:SS`).
     */
    fun secondsToRelTime(seconds: Double): String {
        val totalSec = seconds.toLong().coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs)
    }

    /**
     * Converts UPnP REL_TIME string (`HH:MM:SS` or `H:MM:SS`) to seconds (`Double`).
     */
    fun relTimeToSeconds(relTime: String): Double {
        val parts = relTime.split(":")
        if (parts.size != 3) return 0.0
        val hours = parts[0].toDoubleOrNull() ?: 0.0
        val minutes = parts[1].toDoubleOrNull() ?: 0.0
        val secs = parts[2].toDoubleOrNull() ?: 0.0
        return (hours * 3600.0) + (minutes * 60.0) + secs
    }

    /**
     * Constructs DIDL-Lite metadata XML string for a music track.
     */
    fun buildDidlLiteXml(title: String, artist: String, streamUrl: String, mimeType: String = "audio/mp4"): String {
        val escapedTitle = escapeXml(title)
        val escapedArtist = escapeXml(artist)
        val escapedUrl = escapeXml(streamUrl)
        val protocolInfo = "http-get:*:$mimeType:*"

        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"1\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$escapedTitle</dc:title>" +
            "<upnp:artist>$escapedArtist</upnp:artist>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"$protocolInfo\">$escapedUrl</res>" +
            "</item></DIDL-Lite>"
    }

    /**
     * Builds SOAP XML body for SetAVTransportURI.
     */
    fun buildSetUriBody(streamUrl: String, title: String = "", artist: String = "", mimeType: String = "audio/mp4"): String {
        val didlXml = buildDidlLiteXml(title, artist, streamUrl, mimeType)
        val escapedDidl = escapeXml(didlXml)
        val escapedStreamUrl = escapeXml(streamUrl)

        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:SetAVTransportURI xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "<CurrentURI>$escapedStreamUrl</CurrentURI>" +
            "<CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>" +
            "</u:SetAVTransportURI>" +
            "</s:Body></s:Envelope>"
    }

    /**
     * Builds SOAP XML body for Play action.
     */
    fun buildPlayBody(): String {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:Play xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "<Speed>1</Speed>" +
            "</u:Play>" +
            "</s:Body></s:Envelope>"
    }

    /**
     * Builds SOAP XML body for Pause action.
     */
    fun buildPauseBody(): String {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:Pause xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Pause>" +
            "</s:Body></s:Envelope>"
    }

    /**
     * Builds SOAP XML body for Stop action.
     */
    fun buildStopBody(): String {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:Stop xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Stop>" +
            "</s:Body></s:Envelope>"
    }

    /**
     * Builds SOAP XML body for Seek action.
     */
    fun buildSeekBody(positionSec: Double): String {
        val relTime = secondsToRelTime(positionSec)
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:Seek xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "<Unit>REL_TIME</Unit>" +
            "<Target>$relTime</Target>" +
            "</u:Seek>" +
            "</s:Body></s:Envelope>"
    }

    // --- AVTransport query bodies ---

    /**
     * Builds SOAP XML body for GetPositionInfo (returns current position, duration, and track URI).
     */
    fun buildGetPositionInfoBody(): String =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:GetPositionInfo xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:GetPositionInfo>" +
            "</s:Body></s:Envelope>"

    /**
     * Builds SOAP XML body for GetTransportInfo (returns PLAYING/PAUSED_PLAYBACK/STOPPED state).
     */
    fun buildGetTransportInfoBody(): String =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:GetTransportInfo xmlns:u=\"$AV_TRANSPORT_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:GetTransportInfo>" +
            "</s:Body></s:Envelope>"

    // --- RenderingControl SOAP bodies ---

    /**
     * Builds SOAP XML body for SetVolume (0–100 scale, Master channel).
     */
    fun buildSetVolumeBody(volume: Int): String {
        val clamped = volume.coerceIn(0, 100)
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:SetVolume xmlns:u=\"$RENDERING_CONTROL_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "<Channel>Master</Channel>" +
            "<DesiredVolume>$clamped</DesiredVolume>" +
            "</u:SetVolume>" +
            "</s:Body></s:Envelope>"
    }

    /**
     * Builds SOAP XML body for GetVolume (Master channel).
     */
    fun buildGetVolumeBody(): String =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:GetVolume xmlns:u=\"$RENDERING_CONTROL_NS\">" +
            "<InstanceID>0</InstanceID>" +
            "<Channel>Master</Channel>" +
            "</u:GetVolume>" +
            "</s:Body></s:Envelope>"

    // --- XML response parsing helpers ---

    private val CURRENT_TRACK_DURATION_PATTERN =
        java.util.regex.Pattern.compile("<TrackDuration>(.*?)</TrackDuration>", java.util.regex.Pattern.CASE_INSENSITIVE)
    private val REL_TIME_PATTERN =
        java.util.regex.Pattern.compile("<RelTime>(.*?)</RelTime>", java.util.regex.Pattern.CASE_INSENSITIVE)
    private val TRANSPORT_STATE_PATTERN =
        java.util.regex.Pattern.compile("<CurrentTransportState>(.*?)</CurrentTransportState>", java.util.regex.Pattern.CASE_INSENSITIVE)
    private val CURRENT_VOLUME_PATTERN =
        java.util.regex.Pattern.compile("<CurrentVolume>(\\d+)</CurrentVolume>", java.util.regex.Pattern.CASE_INSENSITIVE)

    /**
     * Parses the RelTime and TrackDuration from a GetPositionInfo SOAP response body.
     * Returns a [PositionInfo] or null when the response cannot be parsed.
     */
    data class PositionInfo(val positionSec: Double, val durationSec: Double)

    fun parsePositionInfo(responseXml: String): PositionInfo? {
        val relTimeMatcher = REL_TIME_PATTERN.matcher(responseXml)
        val durationMatcher = CURRENT_TRACK_DURATION_PATTERN.matcher(responseXml)
        val relTime: String = if (relTimeMatcher.find()) relTimeMatcher.group(1)?.trim() ?: return null else return null
        val duration: String = if (durationMatcher.find()) durationMatcher.group(1)?.trim() ?: return null else return null
        return PositionInfo(relTimeToSeconds(relTime), relTimeToSeconds(duration))
    }

    /**
     * Parses the CurrentTransportState from a GetTransportInfo SOAP response.
     * Returns true if PLAYING, false otherwise.
     */
    fun parseIsPlaying(responseXml: String): Boolean? {
        val matcher = TRANSPORT_STATE_PATTERN.matcher(responseXml)
        if (!matcher.find()) return null
        return matcher.group(1)?.trim()?.uppercase(Locale.ROOT) == "PLAYING"
    }

    /**
     * Parses the CurrentVolume (0–100) from a GetVolume SOAP response.
     */
    fun parseVolume(responseXml: String): Int? {
        val matcher = CURRENT_VOLUME_PATTERN.matcher(responseXml)
        if (!matcher.find()) return null
        return matcher.group(1)?.toIntOrNull()
    }

    // --- HTTP SOAP senders ---

    /**
     * Sends an HTTP SOAP request to the AVTransport service on a Sonos speaker and returns
     * the raw response XML body (null on HTTP error or network failure).
     */
    suspend fun sendSoapRequestForResponse(
        ipAddress: String,
        port: Int = 1400,
        serviceNs: String,
        servicePath: String,
        action: String,
        soapBody: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("http://$ipAddress:$port$servicePath")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"$serviceNs#$action\"")
            }
            val bodyBytes = soapBody.toByteArray(StandardCharsets.UTF_8)
            connection.setRequestProperty("Content-Length", bodyBytes.size.toString())
            connection.outputStream.use { it.write(bodyBytes) }

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                val err = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Timber.w("Sonos SOAP $action failed ${connection.responseCode}: $err")
                null
            }
        }.getOrElse { e ->
            Timber.e(e, "Error sending Sonos SOAP $action to $ipAddress")
            null
        }
    }

    /**
     * Sends an HTTP SOAP request to a Sonos speaker over LAN.
     */
    suspend fun sendSoapRequest(
        ipAddress: String,
        port: Int = 1400,
        action: String,
        soapBody: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val endpointUrl = "http://$ipAddress:$port$AV_TRANSPORT_PATH"
            val url = URL(endpointUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"$AV_TRANSPORT_NS#$action\"")
            }

            val bodyBytes = soapBody.toByteArray(StandardCharsets.UTF_8)
            connection.setRequestProperty("Content-Length", bodyBytes.size.toString())

            connection.outputStream.use { os ->
                os.write(bodyBytes)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                true
            } else {
                val err = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Timber.w("Sonos SOAP action $action failed with code $responseCode: $err")
                false
            }
        }.getOrElse { e ->
            Timber.e(e, "Error sending Sonos SOAP action $action to $ipAddress")
            false
        }
    }

    /**
     * Convenience: sends an AVTransport SOAP request (no response body needed).
     */
    suspend fun sendAvTransportRequest(
        ipAddress: String,
        port: Int = 1400,
        action: String,
        soapBody: String,
    ): Boolean = withContext(Dispatchers.IO) {
        sendSoapRequestForResponse(ipAddress, port, AV_TRANSPORT_NS, AV_TRANSPORT_PATH, action, soapBody) != null
    }

    /**
     * Convenience: sends a RenderingControl SOAP request (no response body needed).
     */
    suspend fun sendRenderingControlRequest(
        ipAddress: String,
        port: Int = 1400,
        action: String,
        soapBody: String,
    ): Boolean = withContext(Dispatchers.IO) {
        sendSoapRequestForResponse(ipAddress, port, RENDERING_CONTROL_NS, RENDERING_CONTROL_PATH, action, soapBody) != null
    }

    /** Queries AVTransport position info; returns null when the SOAP call fails. */
    suspend fun getPositionInfo(ipAddress: String, port: Int = 1400): PositionInfo? =
        sendSoapRequestForResponse(
            ipAddress, port, AV_TRANSPORT_NS, AV_TRANSPORT_PATH,
            "GetPositionInfo", buildGetPositionInfoBody(),
        )?.let { parsePositionInfo(it) }

    /** Queries current transport state; returns true=PLAYING, false=paused/stopped, null=error. */
    suspend fun getIsPlaying(ipAddress: String, port: Int = 1400): Boolean? =
        sendSoapRequestForResponse(
            ipAddress, port, AV_TRANSPORT_NS, AV_TRANSPORT_PATH,
            "GetTransportInfo", buildGetTransportInfoBody(),
        )?.let { parseIsPlaying(it) }

    /** Queries current volume (0–100). Returns null on failure. */
    suspend fun getVolume(ipAddress: String, port: Int = 1400): Int? =
        sendSoapRequestForResponse(
            ipAddress, port, RENDERING_CONTROL_NS, RENDERING_CONTROL_PATH,
            "GetVolume", buildGetVolumeBody(),
        )?.let { parseVolume(it) }

    /** Sets volume (0–100) on the Sonos speaker. Returns true on success. */
    suspend fun setVolume(ipAddress: String, port: Int = 1400, volume: Int): Boolean =
        sendRenderingControlRequest(ipAddress, port, "SetVolume", buildSetVolumeBody(volume))
}
