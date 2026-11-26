package it.mensa

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

/**
 * Notifiche in stile messaggio (MessagingStyle) con azione "Rispondi" (RemoteInput) e
 * "Segna come letto": è il formato che Android Auto legge ad alta voce e a cui fa
 * rispondere dettando. Stesso schema di SegnaOre.
 */
object Notifications {
    const val CHANNEL_ID = "menu_mensa"
    const val NOTIF_ID = 2001
    const val KEY_REPLY = "reply_menu"
    const val ACTION_REPLY = "it.mensa.action.REPLY"
    const val ACTION_MARK_READ = "it.mensa.action.MARK_READ"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun bot() = Person.Builder().setName("Mensa").setKey("mensa").setBot(true).build()
    private fun me() = Person.Builder().setName("Io").setKey("me").build()

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showMenu(context: Context, menu: Menu, heard: String? = null) =
        post(context, heard, Scelta.messaggiMenu(menu), replyable = menu.piatti.isNotEmpty())

    fun showInfo(context: Context, heard: String?, vararg msg: String, replyable: Boolean = true) =
        post(context, heard, msg.toList(), replyable)

    private fun post(context: Context, heard: String?, botMessages: List<String>, replyable: Boolean) {
        if (!canPost(context)) return
        val style = NotificationCompat.MessagingStyle(me()).setGroupConversation(false)
        val all = buildList {
            if (!heard.isNullOrBlank()) add(heard to me())
            botMessages.forEach { add(it to bot()) }
        }
        val now = System.currentTimeMillis()
        all.forEachIndexed { i, (text, person) -> style.addMessage(text, now - (all.size - 1 - i), person) }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_food)
            .setColor(0xFFE5484D.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)

        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Numeri dei piatti").build()
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_food, "Rispondi", actionIntent(context, ACTION_REPLY, mutable = true)
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_food, "Letto", actionIntent(context, ACTION_MARK_READ)
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        // Android Auto vuole sempre l'azione "Rispondi": per i messaggi finali la lasciamo,
        // così si può comunque dire "menu" o cambiare l'ordine.
        builder.addAction(reply).addInvisibleAction(markRead)
        if (!replyable) builder.setTimeoutAfter(15 * 60_000L)

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun actionIntent(context: Context, action: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action)
        val mutability = when {
            mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            mutable -> 0
            else -> PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability
        )
    }
}
