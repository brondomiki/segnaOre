package it.mensa

import android.content.Context
import java.time.DayOfWeek

/** Impostazioni salvate nella memoria privata dell'app. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()

    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var hour: Int
        get() = sp.getInt("hour", 7)
        set(v) = sp.edit().putInt("hour", v).apply()

    var minute: Int
        get() = sp.getInt("minute", 30)
        set(v) = sp.edit().putInt("minute", v).apply()

    var ultimoEsito: String
        get() = sp.getString("ultimo_esito", "") ?: ""
        set(v) = sp.edit().putString("ultimo_esito", v).apply()

    val days: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    fun client() = ItChef(user, pass)
}
