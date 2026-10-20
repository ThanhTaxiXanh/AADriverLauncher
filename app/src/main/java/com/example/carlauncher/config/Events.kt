package com.example.carlauncher.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// repeat: 0 = hằng năm, 1 = hằng tháng, 2 = một lần, 3 = Giao thừa (trước mùng 1 Tết)
// kind: 0 = sự kiện của bạn, 1 = ngày lễ mặc định, 2 = Mùng một / Rằm
data class CalEvent(
    val id: Long,
    val title: String,
    val lunar: Boolean,
    val day: Int,
    val month: Int,
    val year: Int,
    val repeat: Int,
    val kind: Int = 0
)

data class EventLine(val text: String, val countdown: String, val urgent: Boolean, val red: Boolean)

object Events {
    fun parse(raw: String?): List<CalEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CalEvent(
                    o.getLong("id"), o.getString("title"), o.getBoolean("lunar"),
                    o.getInt("day"), o.getInt("month"), o.optInt("year", 0), o.optInt("repeat", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun load(ctx: Context): List<CalEvent> =
        parse(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("events", ""))

    fun save(ctx: Context, list: List<CalEvent>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("lunar", e.lunar)
                    .put("day", e.day).put("month", e.month).put("year", e.year).put("repeat", e.repeat)
            )
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("events", arr.toString()).apply()
    }

    private fun d(id: Long, title: String, lunar: Boolean, day: Int, month: Int, repeat: Int = 0, kind: Int = 1) =
        CalEvent(id, title, lunar, day, month, 0, repeat, kind)

    fun defaults(): List<CalEvent> = listOf(
        d(-1, "Mùng một", true, 1, 1, 1, 2),
        d(-2, "Ngày Rằm", true, 15, 1, 1, 2),
        d(-10, "Tết Dương lịch", false, 1, 1),
        d(-11, "Valentine", false, 14, 2),
        d(-12, "Quốc tế Phụ nữ", false, 8, 3),
        d(-13, "Giải phóng miền Nam", false, 30, 4),
        d(-14, "Quốc tế Lao động", false, 1, 5),
        d(-15, "Quốc tế Thiếu nhi", false, 1, 6),
        d(-16, "Ngày Gia đình VN", false, 28, 6),
        d(-17, "Thương binh Liệt sĩ", false, 27, 7),
        d(-18, "Quốc khánh", false, 2, 9),
        d(-19, "Phụ nữ Việt Nam", false, 20, 10),
        d(-20, "Nhà giáo Việt Nam", false, 20, 11),
        d(-21, "Thành lập QĐND", false, 22, 12),
        d(-22, "Giáng sinh", false, 25, 12),
        d(-30, "Tết Nguyên Đán", true, 1, 1),
        d(-31, "Giao thừa", true, 30, 12, 3),
        d(-32, "Rằm tháng Giêng", true, 15, 1),
        d(-33, "Tết Hàn Thực", true, 3, 3),
        d(-34, "Giỗ Tổ Hùng Vương", true, 10, 3),
        d(-35, "Lễ Phật Đản", true, 15, 4),
        d(-36, "Tết Đoan Ngọ", true, 5, 5),
        d(-37, "Lễ Vu Lan", true, 15, 7),
        d(-38, "Tết Trung Thu", true, 15, 8),
        d(-39, "Ông Công Ông Táo", true, 23, 12)
    )

    private fun solarDate(y: Int, m: Int, d: Int): LocalDate? {
        if (m !in 1..12 || d < 1) return null
        return try {
            LocalDate.of(y, m, minOf(d, YearMonth.of(y, m).lengthOfMonth()))
        } catch (e: Exception) {
            null
        }
    }

    private fun lunarDate(d: Int, m: Int, y: Int): LocalDate? {
        val s = LunarCalendar.lunarToSolar(d, m, y, false) ?: return null
        val ld = try {
            LocalDate.of(s[2], s[1], s[0])
        } catch (e: Exception) {
            return null
        }
        val back = LunarCalendar.solarToLunar(s[0], s[1], s[2])
        if (back.day != d || back.month != m || back.leap) return null
        return ld
    }

    fun nextDate(e: CalEvent, today: LocalDate): LocalDate? {
        val cands = ArrayList<LocalDate>()
        val tl = LunarCalendar.solarToLunar(today.dayOfMonth, today.monthValue, today.year)
        when (e.repeat) {
            0 -> for (dy in -1..2) {
                val d = if (e.lunar) lunarDate(e.day, e.month, tl.year + dy)
                else solarDate(today.year + dy, e.month, e.day)
                if (d != null) cands.add(d)
            }
            1 -> for (i in 0..14) {
                if (e.lunar) {
                    val mm = (tl.month - 1 + i) % 12 + 1
                    val yy = tl.year + (tl.month - 1 + i) / 12
                    lunarDate(e.day, mm, yy)?.let { cands.add(it) }
                } else {
                    val ym = YearMonth.of(today.year, today.monthValue).plusMonths(i.toLong())
                    solarDate(ym.year, ym.monthValue, e.day)?.let { cands.add(it) }
                }
            }
            3 -> for (dy in -1..2) {
                val d = lunarDate(1, 1, tl.year + dy)
                if (d != null) cands.add(d.minusDays(1))
            }
            else -> {
                val d = if (e.lunar) lunarDate(e.day, e.month, e.year) else solarDate(e.year, e.month, e.day)
                if (d != null) cands.add(d)
            }
        }
        return cands.filter { !it.isBefore(today) }.minByOrNull { it.toEpochDay() }
    }

    fun whenText(e: CalEvent): String {
        val al = if (e.lunar) " (ÂL)" else ""
        return when (e.repeat) {
            1 -> "ngày ${e.day}$al hằng tháng"
            2 -> "${e.day}/${e.month}/${e.year}$al"
            else -> "${e.day}/${e.month}$al"
        }
    }

    private fun rank(e: CalEvent, left: Int): Int = if (e.kind == 2 && left > 1) left + 25 else left

    fun lines(raw: String?, today: LocalDate): List<EventLine> {
        val all = parse(raw) + defaults()
        val ups = all.mapNotNull { e ->
            val d = nextDate(e, today) ?: return@mapNotNull null
            Triple(e, d, ChronoUnit.DAYS.between(today, d).toInt())
        }
        val picked = ups.sortedWith(compareBy({ rank(it.first, it.third) }, { it.first.kind }, { it.first.id }))
            .take(2)
            .sortedBy { it.third }
        return picked.map { (e, _, left) ->
            val short = when {
                e.kind == 2 || e.repeat == 3 -> ""
                e.repeat == 1 -> "ngày ${e.day}" + (if (e.lunar) " (ÂL)" else "")
                e.repeat == 2 -> "${e.day}/${e.month}/${e.year}" + (if (e.lunar) " (ÂL)" else "")
                else -> "${e.day}/${e.month}" + (if (e.lunar) " (ÂL)" else "")
            }
            val cd = when {
                left == 0 -> "Hôm nay"
                left <= 30 -> "Còn $left ngày"
                else -> ""
            }
            val text = if (short.isEmpty()) e.title else "${e.title} $short"
            EventLine(text, cd, left <= 3, e.kind == 2)
        }
    }
}
