package com.example.carlauncher.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.carlauncher.config.Events
import com.example.carlauncher.config.LunarCalendar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow

/** Experimental Android Auto location display. Not turn-by-turn navigation. */
class AADriverCarService : CarAppService() {
    // Development-only host validation; restrict trusted hosts before production distribution.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = PositionScreen(carContext)
    }
}

private class PositionScreen(ctx: CarContext) : Screen(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = ctx.getSharedPreferences("aadriver_config", Context.MODE_PRIVATE)
    private var surface: Surface? = null
    private var location: Location? = null
    private var width = 0
    private var height = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mapRenderer = CarMapSnapshotter(ctx)
    private val roadLookup = CarRoadNameLookup()
    private val trip = CarTripStats(ctx)
    private var threeD = true
    private var northUp = false
    private var pendingResetUntil = 0L
    private var roadName = "Đang xác định tên đường..."
    private var mapBitmap: Bitmap? = null
    private var lastMapRequest = 0L
    private var manualCenter: Pair<Double, Double>? = null
    private var manualZoom: Double? = null
    private var manualSince = 0L
    private var followCar = true
    private var renderedCenter: Pair<Double, Double>? = null
    private var renderedFollow = true
    private var renderedZoom = 17.5
    private val vietnamZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val listener = LocationListener { update ->
        if (update.accuracy > 100f && update.hasAccuracy()) return@LocationListener
        location = update
        trip.accept(update)
        requestRoadName()
        requestMap()
        draw()
    }
    private val tick = object : Runnable {
        override fun run() { requestMap(); draw(); handler.postDelayed(this, 1000L) }
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                surface = container.surface
                width = container.width
                height = container.height
                start()
            }
            override fun onScroll(distanceX: Float, distanceY: Float) {
                panMap(distanceX, distanceY)
            }
            override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
                if (focusX <= width * .66f && scaleFactor > 0f) {
                    changeZoom(kotlin.math.log2(scaleFactor.toDouble()))
                }
            }
            override fun onClick(x: Float, y: Float) {
                val w = width.toFloat(); val h = height.toFloat()
                if (x < w*.66f) {
                    // Top-right floating controls.
                    if (y in h*.14f..h*.29f && x > w*.46f) {
                        if (x < w*.56f) northUp = !northUp else threeD = !threeD
                        lastMapRequest = 0L; requestMap(); draw()
                        return
                    }
                    // Left control rail, lower 70% of map.
                    if (x < w*.12f && y in h*.61f..h*.95f) {
                        when {
                            y < h*.725f -> changeZoom(.5)
                            y < h*.835f -> changeZoom(-.5)
                            else -> recenter()
                        }
                        return
                    }
                    // Distance / AVG bottom-right panel: deliberate second tap to reset.
                    if (x > w*.365f && y in h*.855f..h*.955f) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now < pendingResetUntil) { trip.reset(); pendingResetUntil = 0L }
                        else pendingResetUntil = now + 5000L
                        draw()
                    }
                }
            }
            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                stop()
                surface = null
            }
        })
    }
    @SuppressLint("MissingPermission")
    private fun start() {
        handler.removeCallbacks(tick)
        followCar = prefs.getBoolean("follow_car", true)
        handler.post(tick)
        requestRoadName()
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location = it }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener, Looper.getMainLooper())
        } catch (_: Exception) {}
    }
    private fun stop() {
        handler.removeCallbacks(tick)
        trip.save()
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
    }
    private fun recenter() {
        followCar = true
        manualCenter = null
        manualZoom = null
        manualSince = 0L
        lastMapRequest = 0L
        requestMap()
        draw()
    }
    private fun changeZoom(delta: Double) {
        val old = manualZoom ?: prefs.getFloat("map_zoom_float", 17.5f).toDouble()
        manualZoom = (old + delta).coerceIn(3.0,20.0)
        // Zooming does not disable GPS follow. Only panning enters free-map mode.
        manualSince = android.os.SystemClock.elapsedRealtime()
        lastMapRequest = 0L
        requestMap()
    }
    private fun panMap(dx: Float, dy: Float) {
        val loc = location ?: return
        val center = if (followCar) (loc.latitude to loc.longitude) else manualCenter ?: (loc.latitude to loc.longitude)
        val z = manualZoom ?: prefs.getFloat("map_zoom_float",17.5f).toDouble()
        val mapW = width * .635
        val mapH = height * .84
        if (mapW <= 0 || mapH <= 0) return
        // Approximate Web Mercator displacement of a drag in the map viewport.
        val metersPerPx = 156543.03392 * cos(Math.toRadians(center.first)).coerceAtLeast(0.01) / 2.0.pow(z)
        val heading = if (prefs.getBoolean("follow_bearing",true) && loc.hasBearing()) Math.toRadians(loc.bearing.toDouble()) else 0.0
        val east = (dx * cos(heading) + dy * kotlin.math.sin(heading)) * metersPerPx
        val north = (dx * kotlin.math.sin(heading) - dy * cos(heading)) * metersPerPx
        val lat = (center.first + north / 111320.0).coerceIn(-85.0,85.0)
        val lon = center.second - east / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        followCar = false
        manualCenter = lat to (((lon + 540.0) % 360.0) - 180.0)
        manualSince = android.os.SystemClock.elapsedRealtime()
        lastMapRequest = 0L
        requestMap()
    }
    private fun requestRoadName() {
        val current = location ?: return
        roadLookup.refresh(current.latitude,current.longitude) { roadName = it; draw() }
    }
    private fun requestMap() {
        val loc = location ?: return
        if (width <= 0 || height <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMapRequest < 850L) return
        lastMapRequest = now
        val zoom = manualZoom ?: prefs.getFloat("map_zoom_float", 17.5f).toDouble().coerceIn(12.0, 20.0)
        val pitch = if (threeD) prefs.getInt("map_pitch", 45).coerceIn(25, 60).toDouble() else 0.0
        val hour = ZonedDateTime.now(vietnamZone).hour
        val night = when (prefs.getString("map_theme", "auto")) {
            "dark" -> true
            "light" -> false
            else -> hour < 6 || hour >= 18
        }
        val followBearing = prefs.getBoolean("follow_bearing", true)
        val bearing = if (!northUp && followBearing && loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val center = if (followCar) (loc.latitude to loc.longitude) else manualCenter ?: (loc.latitude to loc.longitude)
        mapRenderer.request(center.first, center.second, bearing, zoom, pitch,
            (width * .635f).toInt().coerceAtLeast(64),
            (height * .84f).toInt().coerceAtLeast(64), night) { bitmap ->
            if (bitmap != null) {
                mapBitmap = bitmap
                renderedCenter = center
                renderedFollow = followCar
                renderedZoom = zoom
                draw()
            }
        }
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int) {
        paint.color = color; paint.textSize = size; paint.style = Paint.Style.FILL
        c.drawText(value, x, y, paint)
    }
    private fun draw() {
        val s = surface ?: return
        if (!s.isValid || width <= 0 || height <= 0) return
        val canvas = try { s.lockCanvas(null) } catch (_: Exception) { return }
        try {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawColor(Color.rgb(11, 16, 24))
            val accent = Color.rgb(255, 193, 7)
            val white = Color.WHITE
            val small = (h * 0.037f).coerceAtLeast(13f)
            text(canvas, "AADriverLauncher", w*.04f, h*.085f, small*1.15f, accent)
            val mapRight = w*.66f
            paint.color = Color.rgb(21, 34, 51)
            canvas.drawRoundRect(w*.025f,h*.12f,mapRight,h*.96f,20f,20f,paint)
            val map = mapBitmap
            if (map != null) {
                paint.color = Color.WHITE
                canvas.drawBitmap(map, null, RectF(w*.025f,h*.12f,mapRight,h*.96f), paint)
            } else {
                text(canvas, "Đang tải bản đồ Mapbox...", w*.05f, h*.52f, small, white)
            }
            val cx=(w*.025f+mapRight)/2f; val cy=h*.68f
            // Heading-up car marker; map bearing already rotates the underlying streets.
            val marker = h * .045f
            val vehicle = Path().apply {
                moveTo(cx, cy - marker)
                lineTo(cx - marker*.64f, cy + marker*.75f)
                lineTo(cx, cy + marker*.40f)
                lineTo(cx + marker*.64f, cy + marker*.75f)
                close()
            }
            if (renderedFollow && renderedCenter != null) {
            paint.color = Color.rgb(10, 22, 38)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h*.009f
            canvas.drawPath(vehicle,paint)
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawPath(vehicle,paint)
            }
            // Road-name chip just below the vehicle marker.
            paint.textSize = small*.86f
            val chipLabel = roadName.take(33)
            val chipW = (paint.measureText(chipLabel) + w*.045f).coerceAtMost(w*.50f)
            val chipTop = cy + h*.042f
            paint.color = Color.argb(226, 11, 16, 24)
            canvas.drawRoundRect(cx-chipW/2, chipTop, cx+chipW/2, chipTop+h*.069f, 13f, 13f, paint)
            paint.color = white
            paint.textAlign = Paint.Align.CENTER
            text(canvas,chipLabel,cx,chipTop+h*.048f,small*.86f,white)
            paint.textAlign = Paint.Align.LEFT
            val loc=location
            paint.color = Color.argb(208, 11, 16, 24)
            canvas.drawRoundRect(w*.045f,h*.155f,w*.31f,h*.315f,16f,16f,paint)
            val speed = if (loc==null) "--" else (loc.speed*3.6f).toInt().coerceAtLeast(0).toString()
            text(canvas,"$speed km/h",w*.065f,h*.265f,small*1.6f,accent)

            val right=w*.69f
            val today=ZonedDateTime.now(vietnamZone).toLocalDate()
            val lunar=LunarCalendar.solarToLunar(today.dayOfMonth,today.monthValue,today.year)
            text(canvas,"LỊCH VIỆT NAM",right,h*.19f,small,accent)
            text(canvas,today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),right,h*.28f,small*1.3f,white)
            text(canvas,"Âm lịch: ${lunar.day}/${lunar.month}",right,h*.36f,small,white)
            text(canvas,LunarCalendar.canChi(lunar.year),right,h*.43f,small,white)
            val event=Events.lines(carContext.getSharedPreferences("cfg",Context.MODE_PRIVATE).getString("events",""),today)
            text(canvas,"SỰ KIỆN SẮP TỚI",right,h*.58f,small,accent)
            event.take(2).forEachIndexed { index,e ->
                val line=e.text.take(27)
                text(canvas,line,right,h*(.66f+index*.11f),small*.84f,white)
                text(canvas,e.countdown,right,h*(.71f+index*.11f),small*.7f,accent)
            }
            // Raised map controls matching the approved mockup.
            fun circleButton(x: Float, y: Float, label: String, highlighted: Boolean = false) {
                paint.color = Color.argb(231, 14, 28, 45)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, h*.039f, paint)
                paint.textAlign = Paint.Align.CENTER
                text(canvas,label,x,y+small*.36f,small*.95f,if (highlighted) accent else white)
                paint.textAlign = Paint.Align.LEFT
            }
            circleButton(w*.50f,h*.21f,if (northUp) "N" else "⌖",northUp)
            circleButton(w*.605f,h*.21f,if (threeD) "3D" else "2D",true)
            circleButton(w*.078f,h*.685f,"+")
            circleButton(w*.078f,h*.79f,"−")
            circleButton(w*.078f,h*.895f,"◎")
            paint.color = Color.argb(230, 12, 27, 42)
            canvas.drawRoundRect(w*.36f,h*.77f,w*.645f,h*.95f,16f,16f,paint)
            text(canvas,"Distance",w*.38f,h*.817f,small*.69f,white)
            paint.textAlign = Paint.Align.RIGHT
            text(canvas,String.format(Locale.US,"%.1f km",trip.distanceMeters/1000.0),w*.626f,h*.817f,small*.80f,white)
            paint.textAlign = Paint.Align.LEFT
            text(canvas,"AVG",w*.38f,h*.862f,small*.69f,white)
            paint.textAlign = Paint.Align.RIGHT
            text(canvas,String.format(Locale.US,"%.0f km/h",trip.averageKmh),w*.626f,h*.862f,small*.80f,white)
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.rgb(49,65,80)
            canvas.drawRoundRect(w*.375f,h*.878f,w*.631f,h*.940f,13f,13f,paint)
            paint.textAlign = Paint.Align.CENTER
            val confirmReset = android.os.SystemClock.elapsedRealtime() < pendingResetUntil
            text(canvas,if(confirmReset) "Chạm lại để xóa" else "Reset",w*.503f,h*.923f,small*.73f,white)
            paint.textAlign = Paint.Align.LEFT
        } finally { s.unlockCanvasAndPost(canvas) }
    }
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Về xe").setOnClickListener { recenter() }.build()
        ).build()).build()
    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) { stop(); mapRenderer.close(); roadLookup.close() }
        })
    }
}
