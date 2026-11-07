package com.joyos.launcher.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlin.math.roundToInt

data class GpsData(
    val speedKmh: Int = 0,
    val tripKm: Double = 0.0,
    val latitude: Double = 4.6534, // Default to Bogota until fix
    val longitude: Double = -74.0836,
    val bearing: Float = 0f,
    val altitude: Double = 2600.0,
    val hasFix: Boolean = false
)

/**
 * Subscribes to hardware GPS updates on the automotive head unit (AC8227L)
 * and streams real-time speed (km/h), trip distance, coordinates, and bearing to JoyOS WebView.
 */
class GpsTelemetryManager(
    context: Context,
    private val onGpsUpdate: (data: GpsData) -> Unit
) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var lastLocation: Location? = null
    private var accumulatedMeters: Double = 0.0
    private var isTracking = false
    var currentGpsData = GpsData()
        private set

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L, // 2 Hz update interval for responsive speedometer sweep
                    0.5f,
                    this
                )
                isTracking = true
            }
        } catch (_: SecurityException) {
            // Permission will be granted by user on first launch
        } catch (_: Exception) {
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        isTracking = false
    }

    override fun onLocationChanged(location: Location) {
        val speedMps = if (location.hasSpeed()) location.speed else 0f
        val speedKmh = (speedMps * 3.6f).roundToInt().coerceAtLeast(0)

        lastLocation?.let { prev ->
            val delta = prev.distanceTo(location)
            if (delta in 1.0f..250.0f) {
                accumulatedMeters += delta
            }
        }
        lastLocation = location

        val tripKm = accumulatedMeters / 1000.0
        val data = GpsData(
            speedKmh = speedKmh,
            tripKm = tripKm,
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            hasFix = true
        )
        currentGpsData = data
        onGpsUpdate(data)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
