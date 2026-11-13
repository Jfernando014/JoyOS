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
    val hasFix: Boolean = false,
    val provider: String = "standby",
    val accuracyM: Float = -1f
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
    var sourceMode: String = "tablet" // "tablet" | "phone" | "hybrid"
        private set
    var currentGpsData = GpsData()
        private set

    private var lastGpsFixMs: Long = 0L

    fun setSourceMode(mode: String) {
        val clean = when (mode.lowercase()) {
            "phone", "telefono" -> "phone"
            "hybrid", "hibrido" -> "hybrid"
            else -> "tablet"
        }
        if (clean == sourceMode) return
        sourceMode = clean
        if (isTracking) {
            stopTracking()
            startTracking()
        }
    }

    fun resetTripOdometer() {
        accumulatedMeters = 0.0
        lastLocation = null
        currentGpsData = currentGpsData.copy(tripKm = 0.0)
        onGpsUpdate(currentGpsData)
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            val mgr = locationManager ?: return
            val seedOrder = when (sourceMode) {
                "phone" -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                "hybrid" -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
            }
            val seedLocation = seedOrder.firstNotNullOfOrNull { provider ->
                try {
                    if (mgr.isProviderEnabled(provider)) mgr.getLastKnownLocation(provider) else null
                } catch (_: Throwable) {
                    null
                }
            }
            if (seedLocation != null) {
                emitLocation(seedLocation, isHardwareGps = seedLocation.provider == LocationManager.GPS_PROVIDER)
            }

            if (sourceMode != "phone" && mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L, // 2 Hz update interval for responsive speedometer sweep
                    0.5f,
                    this
                )
                isTracking = true
            }
            if (sourceMode != "tablet" && mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    if (sourceMode == "phone") 2000L else 5000L,
                    if (sourceMode == "phone") 3f else 15f,
                    this
                )
                isTracking = true
            }
            if (sourceMode == "tablet" && !isTracking && mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Fallback if GPS hardware provider is disabled in system settings
                mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 15f, this)
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
        val isHwGps = location.provider == LocationManager.GPS_PROVIDER
        if (sourceMode == "tablet" && !isHwGps && (System.currentTimeMillis() - lastGpsFixMs) < 15000L && lastGpsFixMs > 0L) {
            return
        }
        emitLocation(location, isHardwareGps = isHwGps)
    }

    private fun emitLocation(location: Location, isHardwareGps: Boolean) {
        if (isHardwareGps) {
            lastGpsFixMs = System.currentTimeMillis()
        }
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
            hasFix = true,
            provider = location.provider ?: (if (isHardwareGps) "gps" else "network"),
            accuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        )
        currentGpsData = data
        onGpsUpdate(data)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
