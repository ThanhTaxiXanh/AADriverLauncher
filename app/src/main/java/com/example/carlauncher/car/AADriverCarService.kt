package com.example.carlauncher.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.example.carlauncher.config.Events
import com.example.carlauncher.config.LunarCalendar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Experimental Android Auto location display. Not turn-by-turn navigation. */
class AADriverCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

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
    private val listener = LocationListener { update -> location = update; draw() }
    private val tick = object : Runnable {
        override fun run() { draw(); handler.postDelayed(this, 1000L) }
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                surface = container.surface
                width = container.width
                height = container.height
                start()
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
            // Phase-2 host compatibility test: vehicle-centred GPS display, no map tiles yet.
            val cx=(w*.025f+mapRight)/2f; val cy=h*.54f
            paint.color = Color.rgb(38, 62, 84)
            for(i in -3..3) {
                val offset=i*h*.11f
                canvas.drawRect(w*.04f,cy+offset,mapRight-w*.02f,cy+offset+1.5f,paint)
                canvas.drawRect(cx+offset,h*.14f,cx+offset+1.5f,h*.94f,paint)
            }
            paint.color=accent
            canvas.drawCircle(cx,cy,h*.03f,paint)
            val loc=location
            text(canvas,if(loc==null) "Đang chờ GPS..." else "Vị trí xe • GPS",w*.05f,h*.20f,small,white)
            text(canvas,if(loc==null) "-- km/h" else "${(loc.speed*3.6f).toInt()} km/h",w*.05f,h*.28f,small*1.5f,accent)
            if(loc!=null) {
                text(canvas,String.format(Locale.US,"%.5f, %.5f",loc.latitude,loc.longitude),w*.05f,h*.86f,small*.85f,white)
            }
            val right=w*.69f
            val today=LocalDate.now()
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
            text(canvas,"Bản đồ thực: đang tích hợp",w*.05f,h*.92f,small*.8f,white)
        } finally { s.unlockCanvasAndPost(canvas) }
    }
    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(
            Action.Builder().setTitle("Vị trí").setOnClickListener { draw() }.build()
        ).build()).build()
    override fun onDestroy() { stop(); super.onDestroy() }
}
