package com.example.carlauncher.config

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.*
import java.io.File
import java.time.LocalDate

/** Phone-side configuration only. No launcher/dashboard is rendered on the handset. */
class ConfigActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("aadriver_config", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var eventsBox: LinearLayout
    private lateinit var qrPreview: ImageView
    private val qrFile get() = File(filesDir, "qr.png")
    private val pickQr = 901

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 36) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        title("AADriverLauncher • Cài đặt Android Auto")
        text("Màn hình điện thoại chỉ dùng để cấu hình. Giao diện trên xe sẽ được triển khai ở đợt tiếp theo.")
        section("Bản đồ theo xe")
        toggle("Tự động bám theo vị trí xe", "follow_car", true)
        toggle("Xoay bản đồ theo hướng di chuyển", "follow_bearing", true)
        slider("Mức thu phóng", "map_zoom", 16, 12, 20)
        slider("Độ nghiêng bản đồ", "map_pitch", 45, 0, 60)
        button("Cấp quyền GPS chính xác") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 44)
        }
        section("Lịch Dương / Âm")
        val today = LocalDate.now()
        val lunar = LunarCalendar.solarToLunar(today.dayOfMonth, today.monthValue, today.year)
        text("Dương lịch: ${today.dayOfMonth}/${today.monthValue}/${today.year}")
        text("Âm lịch: ${lunar.day}/${lunar.month}/${lunar.year}${if(lunar.leap) " (tháng nhuận)" else ""} • ${LunarCalendar.canChi(lunar.year)}")
        toggle("Ngày lễ Việt Nam và ngày Âm lịch", "show_holidays", true)
        section("Sự kiện cá nhân")
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(eventsBox)
        button("Thêm sự kiện") { addEventDialog() }
        refreshEvents()
        section("Ảnh mã QR")
        qrPreview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = 400
            setImageDrawable(null)
        }
        root.addView(qrPreview)
        button("Chọn ảnh QR từ điện thoại") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, pickQr)
        }
        button("Xóa ảnh QR") { qrFile.delete(); qrPreview.setImageDrawable(null) }
        refreshQr()
    }
    private fun title(t: String) { root.addView(TextView(this).apply { text = t; textSize = 23f; setPadding(0,0,0,24) }) }
    private fun section(t: String) { root.addView(TextView(this).apply { text=t; textSize=20f; setPadding(0,28,0,12) }) }
    private fun text(t: String) { root.addView(TextView(this).apply { text=t; textSize=15f; setPadding(0,5,0,5) }) }
    private fun button(t: String, action: () -> Unit) { root.addView(Button(this).apply { text=t; setOnClickListener { action() } }) }
    private fun toggle(t:String, key:String, default:Boolean) {
        root.addView(Switch(this).apply {
            text=t; isChecked=prefs.getBoolean(key,default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key,checked).apply() }
        })
    }
    private fun slider(t:String,key:String,default:Int,min:Int,max:Int) {
        val label=TextView(this)
        val seek=SeekBar(this).apply {
            this.max=max-min; progress=prefs.getInt(key,default).coerceIn(min,max)-min
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p:Int, fromUser:Boolean) {
                    label.text="$t: ${p+min}"
                    if(fromUser) prefs.edit().putInt(key,p+min).apply()
                }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        }
        label.text="$t: ${seek.progress+min}"
        root.addView(label); root.addView(seek)
    }
    private fun refreshEvents() {
        eventsBox.removeAllViews()
        val list=Events.load(this)
        if(list.isEmpty()) eventsBox.addView(TextView(this).apply { text="Chưa có sự kiện cá nhân" })
        list.forEach { event ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text="${event.title} — ${Events.whenText(event)}"; layoutParams=LinearLayout.LayoutParams(0,-2,1f) })
            row.addView(Button(this).apply { text="Xóa"; setOnClickListener {
                Events.save(this@ConfigActivity, Events.load(this@ConfigActivity).filter { it.id!=event.id })
                refreshEvents()
            } })
            eventsBox.addView(row)
        }
        val upcoming=Events.lines(prefsForEvents(), LocalDate.now())
        upcoming.forEach { line -> eventsBox.addView(TextView(this).apply { text="${line.text} • ${line.countdown}" }) }
    }
    private fun prefsForEvents()=getSharedPreferences("cfg",MODE_PRIVATE).getString("events","")
    private fun addEventDialog() {
        val wrap=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,8,28,8) }
        val name=EditText(this).apply { hint="Tên sự kiện" }
        val lunar=CheckBox(this).apply { text="Ngày âm lịch" }
        val day=EditText(this).apply { hint="Ngày"; inputType=2 }
        val month=EditText(this).apply { hint="Tháng"; inputType=2 }
        val year=EditText(this).apply { hint="Năm (nếu một lần)"; inputType=2 }
        val repeat=Spinner(this).apply { adapter=ArrayAdapter(this@ConfigActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Hằng năm","Hằng tháng","Một lần")) }
        listOf(name,lunar,day,month,year,repeat).forEach(wrap::addView)
        AlertDialog.Builder(this).setTitle("Thêm sự kiện").setView(wrap).setNegativeButton("Hủy",null).setPositiveButton("Lưu") { _,_ ->
            val d=day.text.toString().toIntOrNull() ?: 0
            val m=month.text.toString().toIntOrNull() ?: 0
            val y=year.text.toString().toIntOrNull() ?: 0
            val rep=repeat.selectedItemPosition
            if(name.text.isBlank() || d !in 1..(if(lunar.isChecked)30 else 31) || m !in 1..12 || (rep==2 && y !in 1900..2200)) {
                Toast.makeText(this,"Ngày hoặc tên sự kiện không hợp lệ",Toast.LENGTH_LONG).show()
            } else {
                val all=Events.load(this).toMutableList()
                all.add(CalEvent(System.currentTimeMillis(),name.text.toString(),lunar.isChecked,d,m,y,rep))
                Events.save(this,all); refreshEvents()
            }
        }.show()
    }
    private fun refreshQr() {
        if(qrFile.exists()) qrPreview.setImageBitmap(BitmapFactory.decodeFile(qrFile.path))
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=pickQr || resultCode!=RESULT_OK) return
        try {
            val uri=data?.data ?: return
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth>0 && bounds.outHeight>0)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600) sample*=2
            val bitmap=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) }
                ?: error("Invalid image")
            qrFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            refreshQr()
        } catch(ex:Exception) { Toast.makeText(this,"Không đọc được ảnh QR",Toast.LENGTH_LONG).show() }
    }
}
