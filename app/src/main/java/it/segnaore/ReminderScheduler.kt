package it.segnaore

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

object ReminderScheduler {
    const val ACTION_DAILY = "it.segnaore.action.DAILY"
    const val ACTION_SNOOZE = "it.segnaore.action.SNOOZED"
    const val EXTRA_DATE = "date"

    private const val REQ_DAILY = 1
    private const val REQ_SNOOZE = 2

    /** Programma il prossimo promemoria giornaliero (cancella quello precedente). */
    fun scheduleNext(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, ACTION_DAILY, REQ_DAILY, null)
        am.cancel(pi)
        if (!prefs.enabled || prefs.days.isEmpty()) return

        val next = nextTrigger(ZonedDateTime.now(), prefs.hour, prefs.minute, prefs.days)
        setAlarm(am, next.toInstant().toEpochMilli(), pi)
    }

    /** Ripropone il promemoria tra [minutes] minuti, per la data indicata. */
    fun snooze(context: Context, date: LocalDate, minutes: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, ACTION_SNOOZE, REQ_SNOOZE, date)
        setAlarm(am, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun nextTrigger(
        now: ZonedDateTime, hour: Int, minute: Int, days: Set<DayOfWeek>
    ): ZonedDateTime {
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        for (offset in 0..7L) {
            val candidate = today.plusDays(offset)
            if (candidate.isAfter(now) && candidate.dayOfWeek in days) return candidate
        }
        return today.plusDays(1)
    }

    private fun setAlarm(am: AlarmManager, atMillis: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Senza permesso "sveglie e promemoria" l'orario può slittare di qualche minuto
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun pendingIntent(
        context: Context, action: String, requestCode: Int, date: LocalDate?
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
        if (date != null) intent.putExtra(EXTRA_DATE, date.toString())
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
