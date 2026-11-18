package it.segnaore

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Regole di calcolo:
 *  - lunedì-venerdì: fino a 8 ore ordinarie, il resto straordinario
 *  - sabato: tutto straordinario
 *  - domenica e festivi nazionali: tutto straordinario festivo
 *  - le ore di viaggio hanno un totale a parte
 */
object WorkCalc {
    const val ORDINARY_HOURS = 8.0

    data class Split(val ordinary: Double, val overtime: Double, val holiday: Double)

    data class Summary(
        val days: Int,
        val worked: Double,
        val ordinary: Double,
        val overtime: Double,
        val holiday: Double,
        val travel: Double
    )

    private val fixedHolidays = setOf(
        MonthDay.of(1, 1), MonthDay.of(1, 6), MonthDay.of(4, 25), MonthDay.of(5, 1),
        MonthDay.of(6, 2), MonthDay.of(8, 15), MonthDay.of(11, 1), MonthDay.of(12, 8),
        MonthDay.of(12, 25), MonthDay.of(12, 26)
    )

    fun easterSunday(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(y, month, day)
    }

    fun isHoliday(date: LocalDate): Boolean =
        MonthDay.from(date) in fixedHolidays || date == easterSunday(date.year).plusDays(1)

    fun split(date: LocalDate, hours: Double): Split = when {
        date.dayOfWeek == DayOfWeek.SUNDAY || isHoliday(date) -> Split(0.0, 0.0, hours)
        date.dayOfWeek == DayOfWeek.SATURDAY -> Split(0.0, hours, 0.0)
        else -> Split(minOf(hours, ORDINARY_HOURS), maxOf(hours - ORDINARY_HOURS, 0.0), 0.0)
    }

    fun summarize(entries: List<Entry>): Summary {
        var days = 0
        var worked = 0.0
        var ord = 0.0
        var extra = 0.0
        var fest = 0.0
        var travel = 0.0
        for (e in entries) {
            val s = split(e.date, e.hours)
            if (e.hours > 0) days++
            worked += e.hours
            ord += s.ordinary
            extra += s.overtime
            fest += s.holiday
            travel += e.travel
        }
        return Summary(days, worked, ord, extra, fest, travel)
    }
}
