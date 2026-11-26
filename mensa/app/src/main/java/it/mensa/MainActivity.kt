package it.mensa

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.scheduleNext(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFE5484D), secondary = Color(0xFF2F6FED))) {
                Surface(Modifier.fillMaxSize()) { Schermata() }
            }
        }
    }
}

@Composable
private fun Schermata() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var user by remember { mutableStateOf(prefs.user) }
    var pass by remember { mutableStateOf(prefs.pass) }
    var mostra by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var hour by remember { mutableStateOf(prefs.hour) }
    var minute by remember { mutableStateOf(prefs.minute) }
    var anticipo by remember { mutableStateOf(prefs.anticipo) }
    var esito by remember { mutableStateOf(prefs.ultimoEsito) }
    var lavoro by remember { mutableStateOf(false) }
    var notifOk by remember { mutableStateOf(Notifications.canPost(ctx)) }
    var exactOk by remember { mutableStateOf(Scheduler.canScheduleExact(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOk = Notifications.canPost(ctx)
    }
    LaunchedEffect(Unit) {
        if (!notifOk && Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {
            delay(1500)
            esito = prefs.ultimoEsito
            notifOk = Notifications.canPost(ctx)
            exactOk = Scheduler.canScheduleExact(ctx)
        }
    }

    fun salva() {
        prefs.user = user; prefs.pass = pass; prefs.enabled = enabled
        prefs.hour = hour; prefs.minute = minute; prefs.anticipo = anticipo
        Scheduler.scheduleNext(ctx)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("🍽 Mensa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Ogni giorno lavorativo ti arriva il menu ITChef come messaggio. In auto Android Auto te lo legge e tu rispondi a voce con i numeri dei piatti.")

        if (!notifOk) Card { Column(Modifier.padding(14.dp)) {
            Text("Le notifiche sono disattivate: senza, il menu non arriva.")
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text("Attiva le notifiche") }
        } }
        if (!exactOk && Build.VERSION.SDK_INT >= 31) Card { Column(Modifier.padding(14.dp)) {
            Text("Concedi \"Sveglie e promemoria\" perché il menu arrivi all'ora esatta.")
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Apri impostazione") }
        } }

        Text("Accesso ITChef", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(user, { user = it }, label = { Text("Nome utente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (mostra) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { mostra = !mostra }) { Text(if (mostra) "Nascondi" else "Mostra") } }
        )

        Text("Promemoria", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(enabled, { enabled = it })
            Spacer(Modifier.width(10.dp))
            Text(if (enabled) "Attivo, dal lunedì al venerdì" else "Disattivato")
        }
        OutlinedButton(onClick = {
            TimePickerDialog(ctx, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
        }) { Text("Orario: %02d:%02d".format(hour, minute)) }

        Text("Ordina per", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(anticipo == 0, { anticipo = 0 }, label = { Text("Oggi") })
            FilterChip(anticipo == 1, { anticipo = 1 }, label = { Text("Giorno lavorativo dopo") })
        }

        Button(onClick = { salva() }, modifier = Modifier.fillMaxWidth()) { Text("Salva") }
        OutlinedButton(
            enabled = !lavoro,
            onClick = {
                salva(); lavoro = true
                thread { Azioni.inviaMenu(ctx.applicationContext); lavoro = false }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (lavoro) "Leggo il menu…" else "Mandami il menu adesso") }

        if (esito.isNotBlank()) Card { Text("Ultima operazione: $esito", Modifier.padding(14.dp)) }

        Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cosa puoi dire", fontWeight = FontWeight.SemiBold)
            Text("• I numeri: \"uno, undici e ventuno\"")
            Text("• I nomi: \"fusilli al tonno, fettina e patate\"")
            Text("• \"Menu\" per riascoltarlo")
            Text("• \"Cosa ho ordinato\"")
            Text("Per cambiare l'ordine basta rispondere di nuovo con altri piatti.")
        } }
        Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Android Auto", fontWeight = FontWeight.SemiBold)
            Text("Come per SegnaOre: in Android Auto → tocca 10 volte \"Versione\" → menu ⋮ → Impostazioni sviluppatore → attiva \"Fonti sconosciute\".")
        } }
    }
}
