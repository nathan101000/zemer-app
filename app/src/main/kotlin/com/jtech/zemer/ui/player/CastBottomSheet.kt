package com.jtech.zemer.ui.player

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jtech.zemer.R
import com.jtech.zemer.models.MediaMetadata
import com.jtech.zemer.playback.CastConnectResult
import com.jtech.zemer.playback.CastLibState
import com.jtech.zemer.playback.PlayerConnection
import com.jtech.zemer.playback.sonos.SonosDevice
import com.jtech.zemer.ui.component.DefaultDialog
import com.jtech.zemer.ui.component.focusBorder
import kotlinx.coroutines.launch
import org.fcast.sender_sdk.DeviceInfo
import com.jtech.zemer.extensions.shareText
import com.jtech.zemer.extensions.copyToClipboard
import com.jtech.zemer.extensions.toast

private const val FCAST_DOWNLOADS_URL = "https://fcast.org/#downloads"

/** Localized message for an on-demand cast-lib failure (never shows the raw, English-only reason). */
@androidx.annotation.StringRes
private fun castFailureMessageRes(reason: CastLibState.Failed.Reason): Int = when (reason) {
    CastLibState.Failed.Reason.UNSUPPORTED_DEVICE -> R.string.cast_unsupported_device
    CastLibState.Failed.Reason.DOWNLOAD_FAILED -> R.string.cast_download_failed
}

private fun shareFcast(context: Context) {
    context.shareText(FCAST_DOWNLOADS_URL)
}

private fun copyFcast(context: Context) {
    context.copyToClipboard("FCast", FCAST_DOWNLOADS_URL, R.string.link_copied)
}

/**
 * The cast device picker, shown via the shared menu bottom-sheet host (LocalMenuState) — same shape as
 * Metrolist's. The FCast native lib isn't bundled, so before any casting it asks for consent to a
 * one-time download (then a spinner), surfaces failures with retry, and offers a receiver-install link
 * with share / copy. Self-contained: collects its own state and resolves the stream URL at connect time.
 *
 * Sonos devices are discovered via SSDP independently of the FCast native lib and appear in the same
 * list. Sonos connect flow mirrors the FCast path: pause local playback, resolve the stream, route it
 * through the phone-side relay, hand the URL to SonosConnector, and dismiss on success.
 */
@Composable
fun CastPicker(
    playerConnection: PlayerConnection,
    mediaMetadata: MediaMetadata?,
    onDismiss: () -> Unit,
) {
    val service = playerConnection.service
    val handler = service.discoveryHandler

    // FCast connection state
    val connectedFcastDevice by handler.connectedDeviceFlow.collectAsState()
    // Sonos connection state — separate from FCast, never share the same session
    val connectedSonosDevice by service.sonosConnector.connectedDevice.collectAsState()
    val isAnythingConnected = connectedFcastDevice != null || connectedSonosDevice != null

    val fcastDevices by handler.discoveredDevicesFlow.collectAsState()
    val sonosDevices by service.sonosProvider.discoveredDevices.collectAsState()
    val deviceItems = remember(fcastDevices, sonosDevices) {
        fcastDevices.map { CastDeviceItem.FCast(it) } + sonosDevices.map { CastDeviceItem.Sonos(it) }
    }
    val libState by service.castLibState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val refreshing by service.castDeviceRefresher.refreshing.collectAsState()

    CastDownloadSuccessEffect(libState)

    // FCast NSD discovery: start once the native lib is ready. Sonos SSDP discovery runs
    // unconditionally via startDiscovery() regardless of FCast lib state.
    LaunchedEffect(Unit) {
        service.sonosProvider.startDiscovery()
    }
    LaunchedEffect(libState) {
        if (libState is CastLibState.Ready) {
            service.startDiscovery()
            service.castDeviceRefresher.refresh()
        }
    }

    // Name of the device a connect is in flight to; blocks further taps and drives the row spinner.
    var connectingDevice by remember { mutableStateOf<String?>(null) }

    fun connect(device: DeviceInfo) {
        if (connectingDevice != null) return
        connectingDevice = device.name
        // Launch on the service scope, not the Activity-bound connection scope: backgrounding the app
        // mid-connect disposes the latter, which would strand the spinner (connectingDevice never
        // resets) and skip CastConnector's timeout abort of the still-pending SDK attempt.
        service.scope.launch {
            try {
                when (val result = service.castConnector.connect(device, mediaMetadata)) {
                    CastConnectResult.Connected -> onDismiss()
                    // No playable stream resolved (transient cipher/poToken/network failure). Don't connect
                    // to a device that would just sit silent — tell the user instead of failing invisibly.
                    CastConnectResult.NoStream ->
                        context.toast(R.string.cast_stream_failed)
                    is CastConnectResult.Failed ->
                        context.toast(context.getString(R.string.cast_connect_failed, result.deviceName))
                }
            } finally {
                connectingDevice = null
            }
        }
    }

    fun connectSonos(device: SonosDevice) {
        if (connectingDevice != null) return
        connectingDevice = device.roomName
        service.scope.launch {
            try {
                val player = service.player
                player.pause()

                val currentId = player.currentMediaItem?.mediaId
                val rawUrl = currentId?.let { service.resolveStreamUrl(it) }
                    ?: service.currentStreamUrl
                if (rawUrl == null) {
                    Toast.makeText(context, R.string.cast_stream_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Route through the phone-side relay so googlevideo binds to the phone's IP, not the
                // Sonos speaker's — the same Stage-2 fix used by FCast (see CastConnector.doConnect).
                service.castStreamRelay.receiverAddress =
                    java.net.InetAddress.getByName(device.ipAddress)
                val streamUrl = currentId?.let { service.relayedStreamUrl(it, rawUrl) } ?: rawUrl
                val mimeType = currentId?.let { service.streamContentType(it) } ?: "audio/mp4"

                val posMs = player.currentPosition
                val durMs = player.duration
                val resumeSec = if (durMs > 0 && posMs >= durMs - 1500) 0.0
                                else (posMs / 1000.0).coerceAtLeast(0.0)

                var connectFailed = false
                service.sonosConnector.connect(
                    device = device,
                    streamUrl = streamUrl,
                    metadata = mediaMetadata,
                    mimeType = mimeType,
                    resumePosSec = resumeSec,
                    onSuccess = { /* spinner cleared in finally */ },
                    onFailure = { connectFailed = true },
                )

                if (connectFailed) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cast_connect_failed, device.roomName),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onDismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.cast_connect_failed, device.roomName),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                connectingDevice = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        CastHeader(
            connected = isAnythingConnected,
            // Reload only makes sense over the device list — not while connected (only the Stop row
            // shows) and not before the lib is ready (no discovery yet).
            showRefresh = libState is CastLibState.Ready && !isAnythingConnected,
            refreshing = refreshing,
            onRefresh = { service.scope.launch { service.castDeviceRefresher.refresh() } },
        )

        when (val s = libState) {
            CastLibState.Idle -> CastCenteredColumn {
                // Sonos devices can still be listed and connected without the FCast lib — show them
                // before the FCast consent prompt if any were found.
                if (sonosDevices.isNotEmpty()) {
                    SonosDeviceRows(
                        devices = sonosDevices,
                        connectedDevice = connectedSonosDevice,
                        connectingDevice = connectingDevice,
                        onConnect = { connectSonos(it) },
                        onDisconnect = { service.sonosConnector.disconnect(); onDismiss() },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                CenteredText(
                    text = stringResource(R.string.cast_consent_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { service.downloadCastLib() }) { Text(stringResource(R.string.cast_download)) }
            }

            is CastLibState.Downloading -> CastCenteredColumn {
                CastDownloadProgress(s.progress)
            }

            is CastLibState.Failed -> CastCenteredColumn {
                CenteredText(stringResource(castFailureMessageRes(s.reason)), style = MaterialTheme.typography.bodyLarge)
                if (s.reason == CastLibState.Failed.Reason.DOWNLOAD_FAILED) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { service.downloadCastLib() }) { Text(stringResource(R.string.retry)) }
                }
            }

            CastLibState.Ready -> when {
                // Sonos is connected — show its stop row (FCast session can't coexist).
                connectedSonosDevice != null -> CastDeviceRow(
                    iconRes = R.drawable.cast_connected,
                    name = connectedSonosDevice!!.roomName,
                    subtitle = stringResource(R.string.connected),
                    trailing = stringResource(R.string.stop_casting),
                    onClick = {
                        service.sonosConnector.disconnect()
                        service.stopCastRelay()
                        onDismiss()
                    },
                )

                // FCast is connected — show its stop row.
                connectedFcastDevice != null -> CastDeviceRow(
                    iconRes = R.drawable.cast_connected,
                    name = connectedFcastDevice!!.name(),
                    subtitle = stringResource(R.string.connected),
                    trailing = stringResource(R.string.stop_casting),
                    onClick = { handler.disconnect(); onDismiss() },
                )

                deviceItems.isEmpty() -> CastCenteredColumn {
                    CastSpinnerText(R.string.cast_searching)
                    Spacer(Modifier.height(4.dp))
                    CenteredText(
                        text = stringResource(R.string.cast_same_wifi_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))
                    CastFcastSection(uriHandler = uriHandler, context = context)
                }

                else -> {
                    deviceItems.forEach { item ->
                        val isConnecting = connectingDevice == item.displayName
                        when (item) {
                            is CastDeviceItem.FCast -> {
                                CastDeviceRow(
                                    iconRes = R.drawable.cast,
                                    name = item.displayName,
                                    subtitle = if (isConnecting) stringResource(R.string.cast_connecting) else null,
                                    connecting = isConnecting,
                                    onClick = { connect(item.info) },
                                )
                            }
                            is CastDeviceItem.Sonos -> {
                                CastDeviceRow(
                                    iconRes = R.drawable.cast,
                                    name = item.displayName,
                                    subtitle = if (isConnecting) {
                                        stringResource(R.string.connecting_to_sonos, item.displayName)
                                    } else {
                                        stringResource(R.string.sonos_speaker)
                                    },
                                    connecting = isConnecting,
                                    onClick = { connectSonos(item.device) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    CastFcastSection(uriHandler = uriHandler, context = context)
                }
            }
        }
    }
}

/**
 * Renders Sonos speaker rows with their current connection/connecting state. Used both in the
 * Ready state device list and as a preview when the FCast lib hasn't been downloaded yet (so Sonos
 * is usable without FCast consent).
 */
@Composable
private fun SonosDeviceRows(
    devices: List<SonosDevice>,
    connectedDevice: SonosDevice?,
    connectingDevice: String?,
    onConnect: (SonosDevice) -> Unit,
    onDisconnect: () -> Unit,
) {
    devices.forEach { device ->
        val isThisConnected = connectedDevice?.udn == device.udn
        val isConnecting = connectingDevice == device.roomName
        CastDeviceRow(
            iconRes = if (isThisConnected) R.drawable.cast_connected else R.drawable.cast,
            name = device.roomName,
            subtitle = when {
                isThisConnected -> stringResource(R.string.connected)
                isConnecting -> stringResource(R.string.connecting_to_sonos, device.roomName)
                else -> stringResource(R.string.sonos_speaker)
            },
            trailing = if (isThisConnected) stringResource(R.string.stop_casting) else null,
            connecting = isConnecting,
            onClick = { if (isThisConnected) onDisconnect() else onConnect(device) },
        )
    }
}

/**
 * Consent dialog shown immediately when casting is enabled in Settings: asks before the one-time
 * download, shows a spinner while downloading, and auto-dismisses once ready (or offers retry on
 * failure). Same states/strings as the picker, so the two are consistent.
 */
@Composable
fun CastDownloadDialog(
    playerConnection: PlayerConnection,
    onDismiss: () -> Unit,
) {
    val service = playerConnection.service
    val libState by service.castLibState.collectAsState()

    CastDownloadSuccessEffect(libState)
    LaunchedEffect(libState) {
        if (libState is CastLibState.Ready) onDismiss()
    }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.cast), contentDescription = null) },
        title = { Text(stringResource(R.string.cast_download_title)) },
        buttons = {
            when (libState) {
                is CastLibState.Downloading -> {}
                is CastLibState.Failed -> {
                    val s = libState as CastLibState.Failed
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    if (s.reason == CastLibState.Failed.Reason.DOWNLOAD_FAILED) {
                        Button(onClick = { service.downloadCastLib() }) { Text(stringResource(R.string.retry)) }
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Button(onClick = { service.downloadCastLib() }) { Text(stringResource(R.string.cast_download)) }
                }
            }
        },
    ) {
        when (val s = libState) {
            is CastLibState.Downloading -> {
                CastDownloadProgress(s.progress)
            }
            is CastLibState.Failed -> {
                CenteredText(stringResource(castFailureMessageRes(s.reason)), style = MaterialTheme.typography.bodyLarge)
            }
            else -> CenteredText(
                text = stringResource(R.string.cast_consent_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastHeader(
    connected: Boolean,
    showRefresh: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Icon(
            painter = painterResource(if (connected) R.drawable.cast_connected else R.drawable.cast),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(if (connected) R.string.casting else R.string.cast_dialog_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (showRefresh) {
            if (refreshing) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                CastCircleButton(iconRes = R.drawable.sync, descRes = R.string.cast_refresh, onClick = onRefresh)
            }
        }
    }
}

/** Receiver-install link with share + copy, so users can take the URL to their TV. */
@Composable
private fun CastFcastSection(uriHandler: androidx.compose.ui.platform.UriHandler, context: Context) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.cast_get_fcast),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .focusBorder(RoundedCornerShape(12.dp))
                .clickable { uriHandler.openUri(FCAST_DOWNLOADS_URL) }
                .padding(vertical = 12.dp, horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CastCircleButton(iconRes = R.drawable.share, descRes = R.string.share) { shareFcast(context) }
            CastCircleButton(iconRes = R.drawable.content_copy, descRes = R.string.copy_link) { copyFcast(context) }
        }
    }
}

@Composable
private fun CastCircleButton(iconRes: Int, descRes: Int, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .focusBorder(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descRes),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CastCenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun CenteredText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    Text(text = text, style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/**
 * The shared downloading body for the picker and the settings dialog: a determinate bar with a percent
 * caption once the total size is known, an indeterminate bar until then.
 */
@Composable
private fun CastDownloadProgress(progress: Float?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        CenteredText(
            text = if (progress != null) {
                stringResource(R.string.cast_downloading_support_percent, (progress * 100).toInt())
            } else {
                stringResource(R.string.cast_downloading_support)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One-shot success toast on the Downloading → Ready transition — only for a download this surface
 * actually watched, never for a lib that was already cached when the surface opened.
 */
@Composable
private fun CastDownloadSuccessEffect(libState: CastLibState) {
    val context = LocalContext.current
    var wasDownloading by remember { mutableStateOf(false) }
    LaunchedEffect(libState) {
        when (libState) {
            is CastLibState.Downloading -> wasDownloading = true
            CastLibState.Ready -> if (wasDownloading) {
                wasDownloading = false
                context.toast(R.string.cast_download_success)
            }
            else -> {}
        }
    }
}

/** Spinner + a centered caption — the shared "working…" body for the downloading and searching states. */
@Composable
private fun CastSpinnerText(@androidx.annotation.StringRes textRes: Int) {
    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    Spacer(Modifier.height(16.dp))
    CenteredText(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CastDeviceRow(
    iconRes: Int,
    name: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String? = null,
    connecting: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .focusBorder(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        if (connecting) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (subtitle != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}
