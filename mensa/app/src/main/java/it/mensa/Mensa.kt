package it.mensa

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MensaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
    }
}

/** Le operazioni vere: leggere il menu e ordinare. Girano sempre fuori dal thread principale. */
object Azioni {
    private val ora = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    private fun esito(context: Context, testo: String) {
        Prefs(context).ultimoEsito = "${LocalDateTime.now().format(ora)} – $testo"
    }

    fun inviaMenu(context: Context, heard: String? = null) {
        val prefs = Prefs(context)
        try {
            val menu = prefs.client().caricaMenu(ItChef.giornoDaPrenotare())
            Notifications.showMenu(context, menu, heard)
            esito(context, "menu inviato (${menu.piatti.size} piatti)")
        } catch (e: Exception) {
            Notifications.showInfo(context, heard, "Non riesco a leggere il menu: ${e.message}")
            esito(context, "errore: ${e.message}")
        }
    }

    /** Per la schermata dell'app: carica il menu (lancia eccezione se non riesce). */
    fun caricaMenu(context: Context): Pair<ItChef, Menu> {
        val client = Prefs(context).client()
        return client to client.caricaMenu(ItChef.giornoDaPrenotare())
    }

    /** Ordine dall'app. Ritorna il messaggio da mostrare. */
    fun ordinaDaApp(context: Context, client: ItChef, menu: Menu, scelti: List<Piatto>): String {
        val errore = client.prenota(menu, scelti)
        val msg = if (errore == null) "Fatto! Ordinato per ${Scelta.giorno(menu.data)}: ${Scelta.elenco(scelti)}."
        else "Il portale non ha accettato l'ordine: $errore"
        esito(context, msg)
        return msg
    }

    fun rispondi(context: Context, testo: String) {
        val prefs = Prefs(context)
        try {
            val client = prefs.client()
            val menu = client.caricaMenu(ItChef.giornoDaPrenotare())
            when (Scelta.comando(testo)) {
                Scelta.Comando.MENU -> { Notifications.showMenu(context, menu, testo); return }
                Scelta.Comando.ORDINE -> {
                    val gia = menu.piatti.filter { it.prenotato }
                    Notifications.showInfo(
                        context, testo,
                        if (gia.isEmpty()) "Per ${Scelta.giorno(menu.data)} non hai ancora ordinato niente."
                        else "Per ${Scelta.giorno(menu.data)} hai ordinato: ${Scelta.elenco(gia)}."
                    )
                    return
                }
                null -> Unit
            }
            if (menu.piatti.isEmpty()) {
                Notifications.showInfo(context, testo, "Non c'è nessun menu per ${Scelta.giorno(menu.data)}.")
                return
            }
            val scelti = Scelta.interpreta(testo, menu.piatti)
            if (scelti.isEmpty()) {
                Notifications.showInfo(
                    context, testo,
                    "Non ho capito. Dimmi i numeri dei piatti, per esempio \"uno, undici e ventuno\", oppure \"menu\" per riascoltarlo."
                )
                return
            }
            val errore = client.prenota(menu, scelti)
            if (errore == null) {
                val msg = "Fatto! Ordinato per ${Scelta.giorno(menu.data)}: ${Scelta.elenco(scelti)}."
                Notifications.showInfo(context, testo, msg, replyable = false)
                esito(context, msg)
            } else {
                Notifications.showInfo(context, testo, "Il portale non ha accettato l'ordine: $errore")
                esito(context, "ordine rifiutato: $errore")
            }
        } catch (e: Exception) {
            Notifications.showInfo(context, testo, "Errore: ${e.message}")
            esito(context, "errore: ${e.message}")
        }
    }
}

/** Sveglia giornaliera (lun-ven all'ora scelta). */
object Scheduler {
    private const val ACTION_DAILY = "it.mensa.action.DAILY"

    fun scheduleNext(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, MenuReceiver::class.java).setAction(ACTION_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (!prefs.enabled) return
        val now = ZonedDateTime.now()
        val base = now.withHour(prefs.hour).withMinute(prefs.minute).withSecond(0).withNano(0)
        val next = (0..7L).map { base.plusDays(it) }.first { it.isAfter(now) && it.dayOfWeek in prefs.days }
        val at = next.toInstant().toEpochMilli()
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

private fun BroadcastReceiver.inBackground(block: () -> Unit) {
    val pending = goAsync()
    thread {
        try { block() } finally { pending.finish() }
    }
}

/** Scatta all'orario scelto: legge il menu e lo manda come notifica. */
class MenuReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Scheduler.scheduleNext(app)
        inBackground { Azioni.inviaMenu(app) }
    }
}

/** Risposta dalla notifica (scritta o dettata in Android Auto). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_REPLY) return // "letto": niente da fare
        val testo = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim().orEmpty()
        val app = context.applicationContext
        inBackground { Azioni.rispondi(app, testo) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.scheduleNext(context)
}
