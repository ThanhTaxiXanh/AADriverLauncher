package com.example.carlauncher.car

import android.content.Context
import android.graphics.Bitmap
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapSnapshotOptions
import com.mapbox.maps.Size
import com.mapbox.maps.Snapshotter
import com.mapbox.maps.Style

/** Asynchronous snapshots for an Android Auto host Surface; no phone Activity required. */
internal class CarMapSnapshotter(private val context: Context) {
    private var snapshotter: Snapshotter? = null
    private var busy = false
    private var active = true
    private var styleUri: String? = null
    private var queued: (() -> Unit)? = null

    fun request(latitude: Double, longitude: Double, bearing: Double, zoom: Double,
                pitch: Double, width: Int, height: Int, darkMode: Boolean, callback: (Bitmap?) -> Unit) {
        if (!active || width <= 0 || height <= 0) return
        if (busy) {
            queued = { request(latitude, longitude, bearing, zoom, pitch, width, height, darkMode, callback) }
            return
        }
        busy = true
        val snap = snapshotter ?: Snapshotter(context, MapSnapshotOptions.Builder()
            .size(Size(width.toFloat(), height.toFloat()))
            .pixelRatio(1.0f)
            .build()).also {
                snapshotter = it
            }
        val desiredStyle = if (darkMode) Style.DARK else Style.LIGHT
        if (styleUri != desiredStyle) {
            snap.setStyleUri(desiredStyle)
            styleUri = desiredStyle
        }
        snap.setSize(Size(width.toFloat(), height.toFloat()))
        snap.setCamera(CameraOptions.Builder()
            .center(Point.fromLngLat(longitude, latitude))
            .zoom(zoom).bearing(bearing).pitch(pitch)
            .padding(EdgeInsets(height * 0.28, 0.0, 0.0, 0.0)).build())
        snap.start { bitmap, _ ->
            busy = false
            if (active) {
                callback(bitmap)
                val next = queued
                queued = null
                next?.invoke()
            }
        }
    }

    fun close() {
        active = false
        queued = null
        snapshotter?.cancel()
        snapshotter?.destroy()
        snapshotter = null
    }
}
