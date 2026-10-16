package com.hocmemini.raid.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Signal(val name: String, val kind: String, val dbm: Int)

/** Nearby Wi-Fi networks and named Bluetooth LE devices, strongest first. Offices are full of them; parks are quiet. */
class SignalScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun scan(): List<Signal> {
        val out = mutableMapOf<String, Signal>()

        // Wi-Fi: trigger a scan (throttled by Android, old results are fine) and read what the system has.
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") wifi.startScan()
            delay(2500)
            @Suppress("DEPRECATION")
            wifi.scanResults.forEach { r ->
                val ssid = (if (android.os.Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") r.SSID) ?: return@forEach
                if (ssid.isBlank()) return@forEach
                val prev = out[ssid]
                if (prev == null || r.level > prev.dbm) out[ssid] = Signal(ssid, "wifi", r.level)
            }
        }

        // Bluetooth LE: 4 s burst, named devices only.
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching
            val scanner = adapter.bluetoothLeScanner ?: return@runCatching
            val found = mutableMapOf<String, Signal>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: result.device?.name ?: return
                    if (name.isBlank()) return
                    val prev = found[name]
                    if (prev == null || result.rssi > prev.dbm) found[name] = Signal(name, "bluetooth", result.rssi)
                }
            }
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            withTimeoutOrNull(4000) { suspendCancellableCoroutine<Unit> { } }
            scanner.stopScan(cb)
            found.forEach { (k, v) -> if (out[k] == null) out[k] = v }
        }

        return out.values.sortedByDescending { it.dbm }
    }
}
