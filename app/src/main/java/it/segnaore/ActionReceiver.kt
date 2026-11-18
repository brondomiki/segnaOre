package it.segnaore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import java.time.LocalDate

/** Gestisce le azioni della notifica: risposta (anche vocale da Android Auto), rapida, rinvio. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HoursRepository.init(context)
        val prefs = Prefs(context)
        val date = intent.getStringExtra(Notifications.EXTRA_DATE)
            ?.let(LocalDate::parse) ?: LocalDate.now()

        when (intent.action) {
            Notifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.KEY_REPLY)
                    ?.toString()?.trim().orEmpty()
                val hours = HoursParser.parse(text, prefs.fullDayHours)
                if (hours == null) {
                    Notifications.showRetry(context, date, text)
                } else {
                    HoursRepository.save(date, hours, text)
                    Notifications.showConfirmation(context, date, hours, text)
                }
            }

            Notifications.ACTION_QUICK -> {
                val hours = intent.getDoubleExtra(Notifications.EXTRA_HOURS, prefs.fullDayHours)
                HoursRepository.save(date, hours, null)
                Notifications.showConfirmation(context, date, hours, null)
            }

            Notifications.ACTION_SNOOZE -> {
                ReminderScheduler.snooze(context, date, prefs.snoozeMinutes)
                Notifications.cancel(context)
            }

            // "Segna come letto" da Android Auto: lasciamo la notifica sul telefono
            Notifications.ACTION_MARK_READ -> Unit
        }
    }
}
