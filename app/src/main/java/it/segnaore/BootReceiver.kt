package it.segnaore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riprogramma il promemoria dopo riavvio, aggiornamento o cambio orario. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.scheduleNext(context)
    }
}
