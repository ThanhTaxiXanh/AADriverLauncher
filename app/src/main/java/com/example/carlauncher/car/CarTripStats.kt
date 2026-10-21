package com.example.carlauncher.car

import android.content.Context
import android.location.Location

/** Persistent driving-session metrics; excludes GPS jumps and time spent parked. */
internal class CarTripStats(context: Context) {
    private val prefs = context.getSharedPreferences("aadriver_trip_stats", Context.MODE_PRIVATE)
    var distanceMeters = prefs.getFloat("distance_m", 0f).toDouble()
        private set
    var movingMillis = prefs.getLong("moving_ms", 0L)
        private set
    private var previous: Location? = null
    private var lastSaved = 0L

    val averageKmh: Double get() =
        if (movingMillis > 0) distanceMeters / (movingMillis / 3600000.0) / 1000.0 else 0.0

    fun accept(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 50f) return
        val old = previous
        previous = Location(location)
        if (old == null) return
        val delta = location.elapsedRealtimeNanos - old.elapsedRealtimeNanos
        val seconds = delta / 1_000_000_000.0
        if (seconds <= 0.0 || seconds > 15.0) return
        val meters = old.distanceTo(location).toDouble()
        val speed = meters / seconds
        if (speed < 0.9 || speed > 45.0 || meters > 150.0) return
        distanceMeters += meters
        movingMillis += (seconds * 1000).toLong()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSaved > 5000L) { save(); lastSaved = now }
    }

    fun reset() {
        distanceMeters = 0.0
        movingMillis = 0L
        previous = null
        save()
    }

    fun save() {
        prefs.edit().putFloat("distance_m", distanceMeters.toFloat())
            .putLong("moving_ms", movingMillis).apply()
    }
}
