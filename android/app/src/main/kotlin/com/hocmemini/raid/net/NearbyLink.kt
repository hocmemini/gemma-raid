package com.hocmemini.raid.net

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.hocmemini.raid.game.Msg
import kotlinx.serialization.json.Json
import android.util.Log
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Offline peer-to-peer link over Google Nearby Connections (Bluetooth / Wi-Fi Direct, no internet).
 * Star topology: one host advertises, players discover and connect to it.
 */
class NearbyLink(
    private val appContext: Context,
    private val localName: String,
    private val onMessage: (endpointId: String, Msg) -> Unit,
    private val onBinary: (endpointId: String, ByteArray) -> Unit,
    private val onPeersChanged: (List<String>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val client = Nearby.getConnectionsClient(appContext)
    private val peers = linkedSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = payload.asBytes() ?: return
                    val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) runCatching { GZIPInputStream(raw.inputStream()).readBytes() }.getOrDefault(raw) else raw
                    runCatching { json.decodeFromString(Msg.serializer(), bytes.decodeToString()) }
                        .onSuccess { Log.d(TAG, "recv ${it::class.simpleName} ${bytes.size}B from $endpointId"); onMessage(endpointId, it) }
                        .onFailure { Log.w(TAG, "bad payload (${bytes.size}B): ${it.message}"); onStatus("bad payload: ${it.message?.take(60)}") }
                }
                Payload.Type.STREAM -> {
                    val stream = payload.asStream()?.asInputStream() ?: return
                    thread(name = "raid-stream") {
                        runCatching { stream.use { it.readBytes() } }
                            .onSuccess { onBinary(endpointId, it) }
                            .onFailure { onStatus("stream failed: ${it.message}") }
                    }
                }
                else -> {}
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "connection initiated with ${info.endpointName} ($endpointId), incoming=${info.isIncomingConnection}")
            onStatus("connecting to ${info.endpointName}…")
            client.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Log.i(TAG, "connection result $endpointId: ${result.status.statusCode} ${result.status.statusMessage}")
            if (result.status.isSuccess) {
                peers += endpointId
                onStatus("connected")
                onPeersChanged(peers.toList())
            } else {
                onStatus("connection failed (${result.status.statusCode}${result.status.statusMessage?.let { " $it" } ?: ""})")
            }
        }
        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "disconnected $endpointId")
            peers -= endpointId
            onPeersChanged(peers.toList())
            onStatus(if (peers.isEmpty()) "disconnected" else "a peer left")
        }
    }

    /** Nearby no longer switches radios on for apps: tell the player what to flip before trying. */
    fun radioProblems(): String? {
        val bt = runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        val wifi = runCatching { appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
        val lm = appContext.getSystemService(android.location.LocationManager::class.java)
        val loc = runCatching { lm?.isLocationEnabled == true }.getOrDefault(true)
        val missing = buildList { if (!bt) add("Bluetooth"); if (!wifi) add("Wi-Fi"); if (!loc) add("Location") }
        return if (missing.isEmpty()) null else "turn on ${missing.joinToString(" and ")} first"
    }

    private var requestedEndpoints = mutableSetOf<String>()

    fun host() {
        radioProblems()?.let { onStatus(it); Log.w(TAG, "host: $it") }
        client.startAdvertising(
            localName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener { Log.i(TAG, "advertising as $localName"); onStatus("hosting as $localName — waiting for players") }
            .addOnFailureListener { Log.w(TAG, "advertise failed: ${it.message}"); onStatus("advertise failed: ${it.message?.take(80)}") }
    }

    fun join() {
        radioProblems()?.let { onStatus(it); Log.w(TAG, "join: $it") }
        requestedEndpoints.clear()
        client.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.i(TAG, "found ${info.endpointName} ($endpointId)")
                    if (!requestedEndpoints.add(endpointId)) return
                    onStatus("found host ${info.endpointName}, connecting…")
                    client.requestConnection(localName, endpointId, lifecycle)
                        .addOnFailureListener { e -> Log.w(TAG, "requestConnection failed: ${e.message}"); requestedEndpoints.remove(endpointId); onStatus("connect failed: ${e.message?.take(60)}") }
                }
                override fun onEndpointLost(endpointId: String) { Log.i(TAG, "lost $endpointId"); requestedEndpoints.remove(endpointId) }
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener { Log.i(TAG, "discovering"); onStatus("searching for a host…") }
            .addOnFailureListener { Log.w(TAG, "discovery failed: ${it.message}"); onStatus("discovery failed: ${it.message?.take(80)}") }
    }

    /** Stop and start discovery again (e.g. the host restarted). */
    fun rejoin() { runCatching { client.stopDiscovery() }; join() }

    /** BYTES payloads are capped at 32 KB: gzip anything over 16 KB, and never send more than the cap. */
    fun send(msg: Msg, to: Collection<String> = peers) {
        if (to.isEmpty()) return
        var bytes = json.encodeToString(Msg.serializer(), msg).toByteArray()
        if (bytes.size > 16_000) {
            val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(bytes) }; bytes = bos.toByteArray()
        }
        if (bytes.size > 31_000) { Log.w(TAG, "payload too large even gzipped (${bytes.size}B): ${msg::class.simpleName} dropped"); return }
        Log.d(TAG, "send ${msg::class.simpleName} ${bytes.size}B to ${to.size}")
        client.sendPayload(to.toList(), Payload.fromBytes(bytes))
    }

    val connected: Boolean get() = peers.isNotEmpty()

    /** Large binary (audio clips) goes as a STREAM payload; BYTES payloads are capped at 32 KB. */
    fun sendStream(bytes: ByteArray, to: Collection<String> = peers) {
        if (to.isEmpty()) return
        client.sendPayload(to.toList(), Payload.fromStream(ByteArrayInputStream(bytes)))
    }

    fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        peers.clear()
    }

    companion object {
        const val SERVICE_ID = "com.hocmemini.raid.v1"
        private const val TAG = "NearbyLink"
    }
}
