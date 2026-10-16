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
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Offline peer-to-peer link over Google Nearby Connections (Bluetooth / Wi-Fi Direct, no internet).
 * Star topology: one host advertises, players discover and connect to it.
 */
class NearbyLink(
    context: Context,
    private val localName: String,
    private val onMessage: (endpointId: String, Msg) -> Unit,
    private val onBinary: (endpointId: String, ByteArray) -> Unit,
    private val onPeersChanged: (List<String>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val client = Nearby.getConnectionsClient(context)
    private val peers = linkedSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    runCatching { json.decodeFromString(Msg.serializer(), bytes.decodeToString()) }
                        .onSuccess { onMessage(endpointId, it) }
                        .onFailure { onStatus("bad payload: ${it.message}") }
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
            onStatus("connecting to ${info.endpointName}…")
            client.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                peers += endpointId
                onStatus("connected")
                onPeersChanged(peers.toList())
            } else {
                onStatus("connection failed (${result.status.statusCode})")
            }
        }
        override fun onDisconnected(endpointId: String) {
            peers -= endpointId
            onPeersChanged(peers.toList())
            onStatus("peer left")
        }
    }

    fun host() {
        client.startAdvertising(
            localName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener { onStatus("hosting as $localName — waiting for players") }
            .addOnFailureListener { onStatus("advertise failed: ${it.message}") }
    }

    fun join() {
        client.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    onStatus("found host ${info.endpointName}")
                    client.requestConnection(localName, endpointId, lifecycle)
                }
                override fun onEndpointLost(endpointId: String) {}
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener { onStatus("searching for a host…") }
            .addOnFailureListener { onStatus("discovery failed: ${it.message}") }
    }

    fun send(msg: Msg, to: Collection<String> = peers) {
        if (to.isEmpty()) return
        val bytes = json.encodeToString(Msg.serializer(), msg).toByteArray()
        client.sendPayload(to.toList(), Payload.fromBytes(bytes))
    }

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
    }
}
