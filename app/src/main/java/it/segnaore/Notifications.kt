package it.segnaore

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le notifiche usano MessagingStyle + azione "Rispondi" con RemoteInput + azione
 * "Segna come letto": è il formato che Android Auto sa leggere ad alta voce e a cui
 * permette di rispondere dettando, senza toccare lo schermo.
 */
object Notifications {
    const val CHANNEL_ID = "promemoria_ore"
    const val NOTIF_ID = 1001
    const val KEY_REPLY = "reply_hours"

    const val ACTION_REPLY = "it.segnaore.action.REPLY"
    const val ACTION_QUICK = "it.segnaore.action.QUICK"
    const val ACTION_SNOOZE = "it.segnaore.action.SNOOZE"
    const val ACTION_MARK_READ = "it.segnaore.action.MARK_READ"
    const val EXTRA_DATE = "date"
    const val EXTRA_HOURS = "hours"

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

    fun dateLabel(date: LocalDate): String = when (date) {
        LocalDate.now() -> "oggi"
        LocalDate.now().minusDays(1) -> "ieri"
        else -> date.format(dayFmt)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc) }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun bot() = Person.Builder().setName("SegnaOre").setKey("segnaore").setBot(true).build()
    private fun me() = Person.Builder().setName("Io").setKey("me").build()

    fun showReminder(context: Context, date: LocalDate) {
        val day = if (date == LocalDate.now()) "oggi" else dateLabel(date)
        val question = "Quante ore hai lavorato $day? Se eri in trasferta aggiungi il viaggio, " +
            "per esempio \"otto e due di viaggio\"."
        post(context, date, listOf(question to bot()), withQuickActions = true)
    }

    fun showRetry(context: Context, date: LocalDate, heard: String) {
        val msg = "Non ho capito \"$heard\". Dimmi le ore, per esempio \"otto\", " +
            "\"sette e mezza\" oppure \"otto e due di viaggio\". " +
            "Puoi anche dire \"riepilogo\" o \"invia report\"."
        post(context, date, listOf(heard to me(), msg to bot()), withQuickActions = true)
    }

    fun showConfirmation(context: Context, date: LocalDate, entry: Entry, heard: String?) {
        val travel = if (entry.travel > 0) " più ${HoursParser.format(entry.travel)} di viaggio" else ""
        val month = YearMonth.from(date)
        val s = WorkCalc.summarize(
            HoursRepository.between(month.atDay(1), month.atEndOfMonth())
        )
        val msg = "Fatto: ${HoursParser.format(entry.hours)}$travel per ${dateLabel(date)}. " +
            "Nel mese: ${HoursParser.format(s.worked)}, di cui ${HoursParser.formatNumber(s.overtime + s.holiday)} di straordinario."
        val messages = buildList {
            if (!heard.isNullOrBlank()) add(heard to me())
            add(msg to bot())
        }
        post(context, date, messages, withQuickActions = false, timeoutMs = 5 * 60_000L)
    }

    /** Messaggio libero del bot (riepilogo, esito report...), con possibilità di rispondere. */
    fun showInfo(context: Context, date: LocalDate, heard: String?, msg: String) {
        val messages = buildList {
            if (!heard.isNullOrBlank()) add(heard to me())
            add(msg to bot())
        }
        post(context, date, messages, withQuickActions = false, timeoutMs = 10 * 60_000L)
    }

    fun summaryText(month: YearMonth): String {
        val s = WorkCalc.summarize(HoursRepository.between(month.atDay(1), month.atEndOfMonth()))
        val name = month.format(DateTimeFormatter.ofPattern("MMMM", Locale.ITALIAN))
        return "Riepilogo di $name: ${s.days} giorni, ${HoursParser.format(s.worked)} lavorate. " +
            "Ordinarie ${HoursParser.formatNumber(s.ordinary)}, " +
            "straordinarie ${HoursParser.formatNumber(s.overtime)}, " +
            "festive ${HoursParser.formatNumber(s.holiday)}, " +
            "viaggio ${HoursParser.formatNumber(s.travel)}."
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID)

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(
        context: Context,
        date: LocalDate,
        messages: List<Pair<String, Person>>,
        withQuickActions: Boolean,
        timeoutMs: Long? = null
    ) {
        if (!canPost(context)) return

        val style = NotificationCompat.MessagingStyle(me()).setGroupConversation(false)
        val now = System.currentTimeMillis()
        messages.forEachIndexed { i, (text, person) ->
            style.addMessage(text, now - (messages.size - 1 - i), person)
        }

        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Quante ore?")
            .setChoices(arrayOf("8", "8 e 2 di viaggio", "4", "Riepilogo"))
            .build()

        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_clock, "Rispondi",
            actionIntent(context, ACTION_REPLY, date, mutable = true)
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()

        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_clock, "Letto",
            actionIntent(context, ACTION_MARK_READ, date)
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setColor(0xFF2F6FED.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addAction(reply)
            .addInvisibleAction(markRead)

        if (withQuickActions) {
            val full = Prefs(context).fullDayHours
            builder.addAction(
                R.drawable.ic_stat_clock, HoursParser.format(full),
                actionIntent(context, ACTION_QUICK, date, hours = full)
            )
            builder.addAction(
                R.drawable.ic_stat_clock, "Più tardi",
                actionIntent(context, ACTION_SNOOZE, date)
            )
        }
        timeoutMs?.let { builder.setTimeoutAfter(it) }

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // permesso notifiche revocato nel frattempo
        }
    }

    private fun actionIntent(
        context: Context,
        action: String,
        date: LocalDate,
        mutable: Boolean = false,
        hours: Double? = null
    ): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_DATE, date.toString())
        if (hours != null) intent.putExtra(EXTRA_HOURS, hours)

        // RemoteInput richiede un PendingIntent mutabile (l'intent è esplicito, quindi è sicuro)
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else if (mutable) {
            0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        val requestCode = action.hashCode() xor date.toEpochDay().toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability
        )
    }
}
