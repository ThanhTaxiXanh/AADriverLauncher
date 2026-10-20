package com.example.carlauncher.car

import android.os.Handler
import android.os.Looper
import com.example.carlauncher.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reverse-geocode the car position without blocking the Android Auto UI thread. */
internal class CarRoadNameLookup {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastTime = 0L

    fun refresh(latitude: Double, longitude: Double, callback: (String) -> Unit) {
        if (closed || busy.get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val meters = FloatArray(1)
        if (!lastLat.isNaN()) android.location.Location.distanceBetween(latitude, longitude, lastLat, lastLon, meters)
        val distanceChanged = lastLat.isNaN() || meters[0] >= 35f
        if (now - lastTime < 20000L && !distanceChanged) return
        if (!busy.compareAndSet(false, true)) return
        lastLat = latitude
        lastLon = longitude
        lastTime = now
        executor.execute {
            try {
                val token = BuildConfig.MAPBOX_ACCESS_TOKEN
                if (token.isBlank() || token == "MAPBOX_TOKEN_HERE") return@execute
                val url = URL("https://api.mapbox.com/search/geocode/v6/reverse?longitude=$longitude&latitude=$latitude&types=street,address&language=vi&limit=1&access_token=${URLEncoder.encode(token, "UTF-8")}")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    if (connection.responseCode != 200) return@execute
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val feature = json.optJSONArray("features")?.optJSONObject(0) ?: return@execute
                    val props = feature.optJSONObject("properties") ?: return@execute
                    val name = props.optString("name", "").trim().ifEmpty {
                        props.optJSONObject("context")?.optJSONObject("street")?.optString("name", "")?.trim().orEmpty()
                    }
                    if (name.isNotEmpty() && !closed) main.post { if (!closed) callback(name) }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Keep the previous road name during temporary network or geocoding failures.
            } finally {
                busy.set(false)
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
