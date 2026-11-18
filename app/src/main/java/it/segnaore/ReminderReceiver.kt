package it.segnaore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/** Scatta all'orario stabilito e mostra la notifica. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HoursRepository.init(context)
        val prefs = Prefs(context)
        val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE)
            ?.let(LocalDate::parse) ?: LocalDate.now()

        if (intent.action == ReminderScheduler.ACTION_DAILY) {
            ReminderScheduler.scheduleNext(context)
            if (prefs.skipIfLogged && HoursRepository.get(date) != null) return
        }
        Notifications.showReminder(context, date)
    }
}
