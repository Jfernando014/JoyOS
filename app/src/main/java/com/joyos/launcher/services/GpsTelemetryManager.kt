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
    private val prefs = context.getSharedPreferences("joyos_gps_prefs", Context.MODE_PRIVATE)
    private var lastLocation: Location? = null
    private var accumulatedMeters: Double = 0.0
    private var isTracking = false
    var sourceMode: String = "hybrid" // "tablet" | "phone" | "hybrid"
        private set
    var currentGpsData = loadInitialGpsData()
        private set

    private var lastGpsFixMs: Long = 0L

    private fun loadInitialGpsData(): GpsData {
        return try {
            if (prefs.contains("last_lat") && prefs.contains("last_lon")) {
                val lat = Double.fromBits(prefs.getLong("last_lat", 0L))
                val lon = Double.fromBits(prefs.getLong("last_lon", 0L))
                val alt = Double.fromBits(prefs.getLong("last_alt", java.lang.Double.doubleToRawLongBits(2600.0)))
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)) {
                    return GpsData(
                        latitude = lat,
                        longitude = lon,
                        altitude = alt,
                        hasFix = false,
                        provider = "cached"
                    )
                }
            }
            GpsData()
        } catch (_: Throwable) {
            GpsData()
        }
    }

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
            val seedCandidates = seedOrder.mapNotNull { provider ->
                try {
                    mgr.getLastKnownLocation(provider)
                } catch (_: Throwable) {
                    null
                }
            }
            val seedLocation = seedCandidates.maxByOrNull { loc ->
                val gpsBonus = if (loc.provider == LocationManager.GPS_PROVIDER) 60000L else 0L
                loc.time + gpsBonus
            }
            if (seedLocation != null) {
                emitLocation(seedLocation, isHardwareGps = seedLocation.provider == LocationManager.GPS_PROVIDER)
            }

            if (sourceMode != "phone" && mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1 Hz hardware GPS stream for speed + map lock without binder flood
                    0.5f,
                    this
                )
                isTracking = true
            }
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Keep network provider active as fast fallback until a live hardware GPS fix arrives
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    if (sourceMode == "phone") 2000L else 3500L,
                    if (sourceMode == "phone") 3f else 10f,
                    this
                )
                isTracking = true
            }
            if (sourceMode != "tablet") {
                try {
                    if (mgr.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                        mgr.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 1f, this)
                        isTracking = true
                    }
                } catch (_: Throwable) {
                }
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
        try {
            if (location.latitude != 0.0 || location.longitude != 0.0) {
                prefs.edit()
                    .putLong("last_lat", java.lang.Double.doubleToRawLongBits(location.latitude))
                    .putLong("last_lon", java.lang.Double.doubleToRawLongBits(location.longitude))
                    .putLong("last_alt", java.lang.Double.doubleToRawLongBits(data.altitude))
                    .apply()
            }
        } catch (_: Throwable) {
        }
        onGpsUpdate(data)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
