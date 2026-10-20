package com.example.carlauncher.car

import android.content.Context
import android.graphics.Bitmap
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapSnapshotOptions
import com.mapbox.maps.Size
import com.mapbox.maps.Snapshotter
import com.mapbox.maps.Style

/** Asynchronous snapshots for an Android Auto host Surface; no phone Activity required. */
internal class CarMapSnapshotter(private val context: Context) {
    private var snapshotter: Snapshotter? = null
    private var busy = false
    private var active = true

    fun request(latitude: Double, longitude: Double, bearing: Double, zoom: Double,
                pitch: Double, width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        if (!active || busy || width <= 0 || height <= 0) return
        busy = true
        val snap = snapshotter ?: Snapshotter(context, MapSnapshotOptions.Builder()
            .size(Size(width.toFloat(), height.toFloat()))
            .pixelRatio(1.0f)
            .build()).also {
                it.setStyleUri(Style.STANDARD)
                snapshotter = it
            }
        snap.setSize(Size(width.toFloat(), height.toFloat()))
        snap.setCamera(CameraOptions.Builder()
            .center(Point.fromLngLat(longitude, latitude))
            .zoom(zoom).bearing(bearing).pitch(pitch).build())
        snap.start { bitmap, _ ->
            busy = false
            if (active) callback(bitmap)
        }
    }

    fun close() {
        active = false
        snapshotter?.cancel()
        snapshotter?.destroy()
        snapshotter = null
    }
}
