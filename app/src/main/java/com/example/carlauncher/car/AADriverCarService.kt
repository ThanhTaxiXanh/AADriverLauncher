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
    private var mapBitmap: Bitmap? = null
    private var lastMapRequest = 0L
    private var manualCenter: Pair<Double, Double>? = null
    private var manualZoom: Double? = null
    private var manualSince = 0L
    private val vietnamZone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val listener = LocationListener { update -> location = update; draw() }
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
                if (x > width * .58f && x < width * .66f && y < height * .42f) {
                    when {
                        y < height * .22f -> changeZoom(1.0)
                        else -> changeZoom(-1.0)
                    }
                } else if (x < width * .66f && y > height * .89f) {
                    recenter()
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
        handler.post(tick)
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location = it }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener, Looper.getMainLooper())
        } catch (_: Exception) {}
    }
    private fun stop() {
        handler.removeCallbacks(tick)
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
    }
    private fun recenter() {
        manualCenter = null
        manualZoom = null
        manualSince = 0L
        lastMapRequest = 0L
        requestMap()
        draw()
    }
    private fun changeZoom(delta: Double) {
        val old = manualZoom ?: prefs.getInt("map_zoom",16).toDouble()
        manualZoom = (old + delta).coerceIn(3.0,20.0)
        manualCenter = manualCenter ?: location?.let { it.latitude to it.longitude }
        manualSince = android.os.SystemClock.elapsedRealtime()
        lastMapRequest = 0L
        requestMap()
    }
    private fun panMap(dx: Float, dy: Float) {
        val loc = location ?: return
        val center = manualCenter ?: (loc.latitude to loc.longitude)
        val z = manualZoom ?: prefs.getInt("map_zoom",16).toDouble()
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
        manualCenter = lat to (((lon + 540.0) % 360.0) - 180.0)
        manualSince = android.os.SystemClock.elapsedRealtime()
        lastMapRequest = 0L
        requestMap()
    }
    private fun requestMap() {
        val loc = location ?: return
        if (width <= 0 || height <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMapRequest < 2500L) return
        lastMapRequest = now
        val zoom = manualZoom ?: prefs.getInt("map_zoom", 16).coerceIn(12, 20).toDouble()
        val pitch = prefs.getInt("map_pitch", 45).coerceIn(0, 60).toDouble()
        val hour = ZonedDateTime.now(vietnamZone).hour
        val night = when (prefs.getString("map_theme", "auto")) {
            "dark" -> true
            "light" -> false
            else -> hour < 6 || hour >= 18
        }
        val followBearing = prefs.getBoolean("follow_bearing", true)
        val bearing = if (followBearing && loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val center = manualCenter ?: (loc.latitude to loc.longitude)
        mapRenderer.request(center.first, center.second, bearing, zoom, pitch,
            (width * .635f).toInt().coerceAtLeast(64),
            (height * .84f).toInt().coerceAtLeast(64), night) { bitmap ->
            if (bitmap != null) {
                mapBitmap = bitmap
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
            val cx=(w*.025f+mapRight)/2f; val cy=h*.54f
            // Heading-up car marker; map bearing already rotates the underlying streets.
            val marker = h * .045f
            val vehicle = Path().apply {
                moveTo(cx, cy - marker)
                lineTo(cx - marker*.64f, cy + marker*.75f)
                lineTo(cx, cy + marker*.40f)
                lineTo(cx + marker*.64f, cy + marker*.75f)
                close()
            }
            paint.color = Color.rgb(10, 22, 38)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h*.009f
            canvas.drawPath(vehicle,paint)
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawPath(vehicle,paint)
            val loc=location
            paint.color = Color.argb(208, 11, 16, 24)
            canvas.drawRoundRect(w*.045f,h*.155f,w*.31f,h*.315f,16f,16f,paint)
            val speed = if (loc==null) "--" else (loc.speed*3.6f).toInt().coerceAtLeast(0).toString()
            text(canvas,"$speed km/h",w*.065f,h*.265f,small*1.6f,accent)
            if(loc!=null) {
                paint.color = Color.argb(190, 11, 16, 24)
                canvas.drawRoundRect(w*.045f,h*.815f,w*.46f,h*.89f,12f,12f,paint)
                text(canvas,String.format(Locale.US,"%.5f, %.5f",loc.latitude,loc.longitude),w*.06f,h*.865f,small*.75f,white)
            }
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
            // Touch targets inside the map: +/- zoom, and bottom-left recenter.
            paint.color = Color.argb(220, 11, 16, 24)
            canvas.drawRoundRect(w*.58f,h*.135f,w*.655f,h*.4f,12f,12f,paint)
            text(canvas,"+",w*.599f,h*.218f,small*1.8f,white)
            text(canvas,"−",w*.599f,h*.355f,small*1.8f,white)
            paint.color = Color.argb(220,11,16,24)
            canvas.drawRoundRect(w*.04f,h*.89f,w*.37f,h*.955f,12f,12f,paint)
            text(canvas,if (manualCenter==null) "Đang theo xe" else "Về vị trí xe",w*.06f,h*.936f,small*.78f,accent)
        } finally { s.unlockCanvasAndPost(canvas) }
    }
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Về xe").setOnClickListener { recenter() }.build()
        ).build()).build()
    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) { stop(); mapRenderer.close() }
        })
    }
}
