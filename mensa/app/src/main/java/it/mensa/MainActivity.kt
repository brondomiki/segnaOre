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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateListOf
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
    var esito by remember { mutableStateOf(prefs.ultimoEsito) }
    var lavoro by remember { mutableStateOf(false) }
    var credOk by remember { mutableStateOf(prefs.user.isNotBlank() && prefs.pass.isNotBlank()) }
    var versione by remember { mutableStateOf(0) }
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
        prefs.hour = hour; prefs.minute = minute
        Scheduler.scheduleNext(ctx)
        credOk = user.isNotBlank() && pass.isNotBlank()
        versione++
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("🍽 Mensa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Ogni giorno lavorativo ti arriva il menu ITChef come messaggio. In auto Android Auto te lo legge e tu rispondi a voce con i numeri dei piatti.")

        if (credOk) androidx.compose.runtime.key(versione) { MenuOggi() }

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

        Text("Fino alle 8:30 l'app ordina per oggi, dopo per il giorno lavorativo successivo.",
            style = MaterialTheme.typography.bodySmall)

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

@Composable
private fun MenuOggi() {
    val ctx = LocalContext.current
    var stato by remember { mutableStateOf("Leggo il menu…") }
    var dati by remember { mutableStateOf<Pair<ItChef, Menu>?>(null) }
    val scelti = remember { mutableStateListOf<String>() }
    var occupato by remember { mutableStateOf(true) }

    fun carica() {
        occupato = true; stato = "Leggo il menu…"
        thread {
            try {
                val r = Azioni.caricaMenu(ctx.applicationContext)
                dati = r
                scelti.clear(); scelti.addAll(r.second.piatti.filter { it.prenotato }.map { it.code })
                stato = if (r.second.piatti.isEmpty()) "Nessun menu per ${Scelta.giorno(r.second.data)}." else ""
            } catch (e: Exception) {
                stato = "Non riesco a leggere il menu: ${e.message}"
            }
            occupato = false
        }
    }
    LaunchedEffect(Unit) { carica() }

    Card { Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val menu = dati?.second
        Text(
            if (menu != null) "Menu di ${Scelta.giorno(menu.data)}" else "Menu",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
        )
        if (stato.isNotBlank()) Text(stato)
        if (menu != null && menu.piatti.isNotEmpty()) {
            val titoli = mapOf("Primo" to "Primi", "Secondo" to "Secondi", "Contorno" to "Contorni", "PiattoUnico" to "Piatto unico")
            for ((cat, piatti) in menu.piatti.groupBy { it.categoria }) {
                Spacer(Modifier.width(1.dp))
                Text(titoli[cat] ?: cat, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                for (p in piatti) {
                    val on = p.code in scelti
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) scelti.remove(p.code) else scelti.add(p.code) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, { if (it) scelti.add(p.code) else scelti.remove(p.code) })
                        Text("${p.n}. ${Scelta.bello(p.nome)}")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !occupato && scelti.isNotEmpty(), onClick = {
                    val (client, m) = dati ?: return@Button
                    val lista = m.piatti.filter { it.code in scelti }
                    occupato = true; stato = "Invio l'ordine…"
                    thread {
                        stato = try { Azioni.ordinaDaApp(ctx.applicationContext, client, m, lista) }
                        catch (e: Exception) { "Errore: ${e.message}" }
                        occupato = false
                    }
                }) { Text("Ordina") }
                OutlinedButton(enabled = !occupato, onClick = { carica() }) { Text("Aggiorna") }
            }
        } else if (!occupato) {
            OutlinedButton(onClick = { carica() }) { Text("Riprova") }
        }
    } }
}
