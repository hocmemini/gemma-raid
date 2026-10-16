package com.hocmemini.raid.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Compass heading of the camera's line of sight (phone held upright), degrees 0..360, 0 = north. */
class Compass(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothed = Float.NaN

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Phone held vertically, camera facing forward: remap so azimuth follows the camera axis.
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val deg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
        // Exponential smoothing on the unit circle to avoid the 359→0 jump.
        smoothed = if (smoothed.isNaN()) deg else {
            val d = ((deg - smoothed + 540f) % 360f) - 180f
            (smoothed + 0.25f * d + 360f) % 360f
        }
        _heading.value = smoothed
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Fused GPS location, updated every couple of seconds. */
class Locator(context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _location.value = it }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        fused.lastLocation.addOnSuccessListener { it?.let { l -> _location.value = l } }
    }
    fun stop() = fused.removeLocationUpdates(callback)
}

/** Distance in meters between two lat/lon points. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val out = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return out[0]
}

/** Steps since the last reset. Counts per-step detector events (instant) and falls back to the batched hardware counter. */
class StepCounter(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val _sinceReset = MutableStateFlow(0)
    val sinceReset: StateFlow<Int> = _sinceReset
    private var detected = 0
    private var base = -1f
    private var latest = 0f

    fun start() {
        detector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        counter?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }
    fun stop() = sm.unregisterListener(this)
    fun reset() { detected = 0; base = latest; _sinceReset.value = 0 }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor) {
            detector -> detected += 1
            counter -> { latest = e.values[0]; if (base < 0) base = latest }
        }
        val fromCounter = if (base >= 0) (latest - base).toInt() else 0
        _sinceReset.value = maxOf(detected, fromCounter)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
