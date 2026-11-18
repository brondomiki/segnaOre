package it.segnaore

import android.content.Context
import java.time.DayOfWeek

/** Impostazioni del promemoria salvate in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var hour: Int
        get() = sp.getInt("hour", 18)
        set(v) = sp.edit().putInt("hour", v).apply()

    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = sp.edit().putInt("minute", v).apply()

    /** Giorni in cui arriva il promemoria (default lun-ven). */
    var days: Set<DayOfWeek>
        get() = (sp.getStringSet("days", null) ?: setOf("1", "2", "3", "4", "5"))
            .mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }
            .toSet()
        set(v) = sp.edit().putStringSet("days", v.map { it.value.toString() }.toSet()).apply()

    /** Ore di una giornata piena, usate per il pulsante rapido e per "giornata intera". */
    var fullDayHours: Double
        get() = sp.getFloat("full_day", 8f).toDouble()
        set(v) = sp.edit().putFloat("full_day", v.toFloat()).apply()

    /** Se le ore di oggi sono già segnate, non inviare il promemoria. */
    var skipIfLogged: Boolean
        get() = sp.getBoolean("skip_if_logged", true)
        set(v) = sp.edit().putBoolean("skip_if_logged", v).apply()

    var snoozeMinutes: Int
        get() = sp.getInt("snooze", 30)
        set(v) = sp.edit().putInt("snooze", v).apply()
}
