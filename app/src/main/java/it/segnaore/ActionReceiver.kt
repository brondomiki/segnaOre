package it.segnaore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Gestisce le azioni della notifica: risposta scritta o vocale (Android Auto),
 * pulsante rapido e rinvio. Comandi vocali riconosciuti oltre alle ore:
 * "riepilogo" / "totale del mese", "invia report", "cancella".
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                handle(app, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        HoursRepository.init(context)
        val prefs = Prefs(context)
        val date = intent.getStringExtra(Notifications.EXTRA_DATE)
            ?.let(LocalDate::parse) ?: LocalDate.now()

        when (intent.action) {
            Notifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.KEY_REPLY)
                    ?.toString()?.trim().orEmpty()
                handleText(context, date, text, prefs)
            }

            Notifications.ACTION_QUICK -> {
                val hours = intent.getDoubleExtra(Notifications.EXTRA_HOURS, prefs.fullDayHours)
                val travel = HoursRepository.get(date)?.travel ?: 0.0
                HoursRepository.save(date, hours, travel, null)
                syncQuietly(context)
                Notifications.showConfirmation(context, date, HoursRepository.get(date)!!, null)
            }

            Notifications.ACTION_SNOOZE -> {
                ReminderScheduler.snooze(context, date, prefs.snoozeMinutes)
                Notifications.cancel(context)
            }

            // "Segna come letto" da Android Auto: lasciamo la notifica sul telefono
            Notifications.ACTION_MARK_READ -> Unit
        }
    }

    private fun handleText(context: Context, date: LocalDate, text: String, prefs: Prefs) {
        when (HoursParser.command(text)) {
            HoursParser.Command.SUMMARY -> {
                syncQuietly(context)
                Notifications.showInfo(context, date, text, Notifications.summaryText(YearMonth.from(date)))
                return
            }

            HoursParser.Command.REPORT -> {
                val month = YearMonth.from(date)
                val name = month.format(DateTimeFormatter.ofPattern("MMMM", Locale.ITALIAN))
                val msg = try {
                    val to = Cloud.sendReport(context, month = month.toString())
                    "Report di $name inviato a $to."
                } catch (e: Exception) {
                    "Non sono riuscito a inviare il report: ${e.message}"
                }
                Notifications.showInfo(context, date, text, msg)
                return
            }

            HoursParser.Command.DELETE -> {
                HoursRepository.delete(date)
                syncQuietly(context)
                Notifications.showInfo(
                    context, date, text,
                    "Ho cancellato le ore di ${Notifications.dateLabel(date)}. Dimmi le ore corrette quando vuoi."
                )
                return
            }

            null -> Unit
        }

        val parsed = HoursParser.parseFull(text, prefs.fullDayHours)
        if (parsed == null) {
            Notifications.showRetry(context, date, text)
            return
        }
        val existing = HoursRepository.get(date)
        val hours = parsed.hours ?: existing?.hours ?: 0.0
        val travel = parsed.travel ?: existing?.travel ?: 0.0
        HoursRepository.save(date, hours, travel, text)
        syncQuietly(context)
        Notifications.showConfirmation(context, date, HoursRepository.get(date)!!, text)
    }

    private fun syncQuietly(context: Context) {
        if (!Cloud.isLoggedIn(context)) return
        try {
            Cloud.sync(context)
        } catch (_: Exception) {
            // riproverà alla prossima apertura dell'app
        }
    }
}
