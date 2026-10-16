package com.hocmemini.raid.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Light, barometric depth and phone heat — flavor the dungeon master's view of the room. */
class Environment(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gyroTemp = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == "com.google.sensor.gyro_temperature" }

    private var lux = Float.NaN
    private var hPa = Float.NaN
    private var baseHPa = Float.NaN
    private var tempC = Float.NaN
    private val _summary = MutableStateFlow("")
    /** Short HUD string like "🌑 dark · ⬇2 floors". */
    val summary: StateFlow<String> = _summary

    fun start() {
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroTemp?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    fun stop() = sm.unregisterListener(this)
    /** Call when the raid starts so floors are relative to the starting point. */
    fun markBaseline() { if (!hPa.isNaN()) baseHPa = hPa }

    /** Floors relative to the baseline: ~8.3 m per hPa, ~3.5 m per floor. */
    fun floors(): Int = if (hPa.isNaN() || baseHPa.isNaN()) 0 else ((baseHPa - hPa) * 8.3f / 3.5f).roundToInt()
    fun isDark(): Boolean = !lux.isNaN() && lux < 15f

    /** Sentence for the spawn prompt, or empty when nothing is notable. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        when {
            lux.isNaN() -> {}
            lux < 15f -> parts += "it is pitch dark here"
            lux < 80f -> parts += "the light is dim"
            lux > 3000f -> parts += "it is bright daylight"
        }
        val f = floors()
        if (abs(f) >= 1) parts += "the players are about ${abs(f)} floor${if (abs(f) > 1) "s" else ""} ${if (f > 0) "above" else "below"} where the raid began"
        if (!tempC.isNaN() && tempC > 42f) parts += "the air feels hot"
        return parts.joinToString("; ")
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor) {
            light -> lux = e.values[0]
            pressure -> { hPa = e.values[0]; if (baseHPa.isNaN()) baseHPa = hPa }
            gyroTemp -> tempC = e.values[0]
        }
        val hud = mutableListOf<String>()
        if (isDark()) hud += "🌑 dark"
        val f = floors(); if (f >= 1) hud += "⬆$f floors" else if (f <= -1) hud += "⬇${-f} floors"
        if (!tempC.isNaN() && tempC > 42f) hud += "🔥 hot"
        _summary.value = hud.joinToString(" · ")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Detects a vigorous swing of the phone (linear acceleration spike). Emits the peak magnitude in m/s². */
class SwingDetector(context: Context, private val onSwing: (peak: Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var lastSwingAt = 0L
    private var peak = 0f
    private var inSwing = false

    fun start() { sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val now = System.currentTimeMillis()
        if (mag > THRESHOLD) { inSwing = true; if (mag > peak) peak = mag }
        else if (inSwing && mag < 4f) {
            inSwing = false
            if (now - lastSwingAt > COOLDOWN_MS) { lastSwingAt = now; onSwing(peak) }
            peak = 0f
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { const val THRESHOLD = 16f; const val COOLDOWN_MS = 1500L }
}

/** Pixel "double twist" gesture sensor (vendor type), fires once per double twist. */
class DoubleTwist(context: Context, private val onTwist: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == "com.google.sensor.double_twist" }
    val available: Boolean get() = sensor != null
    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() = sm.unregisterListener(this)
    override fun onSensorChanged(e: SensorEvent) { onTwist() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** 0..1 loudness of a 16-bit mono WAV (RMS, roughly: whisper 0, conversation ~0.3, shout ~1). */
fun wavLoudness(wav: ByteArray): Float {
    if (wav.size <= 44 + 2) return 0f
    var sum = 0.0; var n = 0
    var i = 44
    while (i + 1 < wav.size) {
        val s = ((wav[i + 1].toInt() shl 8) or (wav[i].toInt() and 0xff)).toShort().toInt()
        sum += s.toDouble() * s; n++; i += 2
    }
    val rms = sqrt(sum / n)
    return ((rms - 300.0) / 5000.0).coerceIn(0.0, 1.0).toFloat()
}
