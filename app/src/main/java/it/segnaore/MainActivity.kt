package it.segnaore

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HoursRepository.init(this)
        Cloud.refreshStatus(this)
        ReminderScheduler.scheduleNext(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            SegnaOreTheme {
                MainScreen(
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    },
                    requestExactAlarms = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SegnaOreTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val rowDateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ITALIAN)
private val shortDateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
private fun n(v: Double) = HoursParser.formatNumber(v)
private fun String.cap() = replaceFirstChar { it.uppercase() }

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d ->
        onPicked(LocalDate.of(y, m + 1, d))
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun parseTravel(text: String, fullDay: Double): Double? =
    if (text.isBlank()) 0.0 else HoursParser.parse(text, fullDay)

/** Esegue un'operazione di rete in background mostrando l'esito. */
private fun runCloud(
    scope: CoroutineScope,
    setBusy: (Boolean) -> Unit,
    toast: (String) -> Unit,
    block: () -> String
) {
    scope.launch {
        setBusy(true)
        val msg = withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                e.message ?: "Errore"
            }
        }
        setBusy(false)
        toast(msg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(requestNotifications: () -> Unit, requestExactAlarms: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val entries by HoursRepository.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeTick++
        Cloud.syncAsync(context)
    }

    val thisMonth = YearMonth.now()
    var from by remember { mutableStateOf(thisMonth.atDay(1)) }
    var to by remember { mutableStateOf(thisMonth.atEndOfMonth()) }
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }

    val filtered = entries.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    val fileSuffix = "${from}_$to"

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = "﻿" + HoursRepository.toCsv(filtered)
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            toast("CSV salvato")
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            runCloud(scope, { busy = it }, toast) {
                val bytes = Cloud.downloadReport(context, from, to)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "PDF salvato"
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SegnaOre") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TodayCard(
                    entry = entries.firstOrNull { it.date == LocalDate.now() },
                    fullDay = prefs.fullDayHours,
                    onSaved = { Cloud.syncAsync(context) }
                )
            }
            item {
                FilterCard(
                    from = from, to = to, list = filtered, busy = busy,
                    onRange = { f, t -> from = f; to = t },
                    onCsv = { csvLauncher.launch("ore_$fileSuffix.csv") },
                    onPdf = {
                        if (!Cloud.isLoggedIn(context)) toast("Per il PDF accedi al tuo account (in fondo)")
                        else pdfLauncher.launch("ore_$fileSuffix.pdf")
                    },
                    onEmail = {
                        if (!Cloud.isLoggedIn(context)) toast("Per l'email accedi al tuo account (in fondo)")
                        else runCloud(scope, { busy = it }, toast) {
                            val sentTo = Cloud.sendReport(context, from, to)
                            "Report inviato a $sentTo"
                        }
                    },
                    onAddDay = { pickDate(context, LocalDate.now()) { editing = it } }
                )
            }
            item {
                Text(
                    "Giorni nel periodo (${filtered.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "Nessuna ora registrata in questo periodo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(filtered, key = { it.date.toString() }) { e ->
                EntryRow(
                    e,
                    onClick = { editing = e.date },
                    onDelete = {
                        HoursRepository.delete(e.date)
                        Cloud.syncAsync(context)
                    }
                )
            }
            item {
                ArchiveCard(entries) { ym ->
                    from = ym.atDay(1)
                    to = ym.atEndOfMonth()
                }
            }
            item { AccountCard(scope, toast) }
            item {
                key(resumeTick) {
                    ReminderCard(prefs, requestNotifications, requestExactAlarms)
                }
            }
        }
    }

    editing?.let { date ->
        EditDialog(
            date = date,
            initial = HoursRepository.get(date),
            fullDay = prefs.fullDayHours,
            onDismiss = { editing = null },
            onSaved = { Cloud.syncAsync(context) }
        )
    }
}

@Composable
fun TodayCard(entry: Entry?, fullDay: Double, onSaved: () -> Unit) {
    var hoursText by remember { mutableStateOf("") }
    var travelText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()

    fun save(hours: Double, travel: Double, note: String?) {
        HoursRepository.save(today, hours, travel, note)
        hoursText = ""
        travelText = ""
        error = null
        onSaved()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).cap(),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                entry?.let {
                    HoursParser.format(it.hours) +
                        if (it.travel > 0) " + ${n(it.travel)} viaggio" else ""
                } ?: "Ore non ancora segnate",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (entry != null) {
                val s = WorkCalc.split(today, entry.hours)
                Text(
                    "Ordinarie ${n(s.ordinary)} · Straordinarie ${n(s.overtime)} · Festive ${n(s.holiday)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it; error = null },
                    label = { Text("Ore lavorate") },
                    placeholder = { Text("8 / sette e mezza") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = travelText,
                    onValueChange = { travelText = it; error = null },
                    label = { Text("Viaggio") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    modifier = Modifier.weight(0.7f)
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val h = HoursParser.parse(hoursText, fullDay)
                    val t = parseTravel(travelText, fullDay)
                    when {
                        h == null -> error = "Ore non valide"
                        t == null -> error = "Ore di viaggio non valide"
                        else -> save(h, t, hoursText)
                    }
                }) { Text("Salva") }
                AssistChip(onClick = { save(fullDay, entry?.travel ?: 0.0, null) }, label = { Text(n(fullDay)) })
                AssistChip(onClick = { save(0.0, 0.0, "ferie") }, label = { Text("Ferie") })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterCard(
    from: LocalDate,
    to: LocalDate,
    list: List<Entry>,
    busy: Boolean,
    onRange: (LocalDate, LocalDate) -> Unit,
    onCsv: () -> Unit,
    onPdf: () -> Unit,
    onEmail: () -> Unit,
    onAddDay: () -> Unit
) {
    val context = LocalContext.current
    val s = WorkCalc.summarize(list)
    val now = YearMonth.now()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Periodo", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickDate(context, from) { f -> onRange(f, if (to.isBefore(f)) f else to) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Dal ${from.format(shortDateFmt)}") }
                OutlinedButton(
                    onClick = { pickDate(context, to) { t -> onRange(if (from.isAfter(t)) t else from, t) } },
                    modifier = Modifier.weight(1f)
                ) { Text("Al ${to.format(shortDateFmt)}") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onRange(now.atDay(1), now.atEndOfMonth()) }, label = { Text("Questo mese") })
                AssistChip(onClick = {
                    val p = now.minusMonths(1)
                    onRange(p.atDay(1), p.atEndOfMonth())
                }, label = { Text("Mese scorso") })
                AssistChip(onClick = {
                    onRange(LocalDate.of(now.year, 1, 1), LocalDate.of(now.year, 12, 31))
                }, label = { Text("Anno") })
            }

            HorizontalDivider()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Stat("Giorni", s.days.toString())
                Stat("Lavorate", n(s.worked))
                Stat("Ordinarie", n(s.ordinary))
                Stat("Straordinarie", n(s.overtime))
                Stat("Festive", n(s.holiday))
                Stat("Viaggio", n(s.travel))
            }
            HorizontalDivider()

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCsv) { Text("Export CSV") }
                FilledTonalButton(onClick = onPdf, enabled = !busy) { Text("PDF") }
                FilledTonalButton(onClick = onEmail, enabled = !busy) { Text("Invia email") }
                TextButton(onClick = onAddDay) { Text("+ Altro giorno") }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EntryRow(entry: Entry, onClick: () -> Unit, onDelete: () -> Unit) {
    val s = WorkCalc.split(entry.date, entry.hours)
    val parts = buildList {
        if (s.ordinary > 0) add("ord. ${n(s.ordinary)}")
        if (s.overtime > 0) add("straord. ${n(s.overtime)}")
        if (s.holiday > 0) add("festive ${n(s.holiday)}")
        if (entry.travel > 0) add("viaggio ${n(entry.travel)}")
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.date.format(rowDateFmt), style = MaterialTheme.typography.bodyLarge)
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (!entry.note.isNullOrBlank()) {
                    Text(
                        "\"${entry.note}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(HoursParser.format(entry.hours), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Elimina")
            }
        }
        HorizontalDivider()
    }
}

@Composable
fun ArchiveCard(entries: List<Entry>, onMonth: (YearMonth) -> Unit) {
    val months = entries.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it })
    if (months.isEmpty()) return
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Archivio mensile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            months.forEach { (ym, list) ->
                val s = WorkCalc.summarize(list)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onMonth(ym) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ym.format(monthFmt).cap(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "straord. ${n(s.overtime)} · festive ${n(s.holiday)} · viaggio ${n(s.travel)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(HoursParser.format(s.worked), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AccountCard(scope: CoroutineScope, toast: (String) -> Unit) {
    val context = LocalContext.current
    val st by Cloud.status.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("brondomiki@gmail.com") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Account e sito web", style = MaterialTheme.typography.titleMedium)
            if (!st.loggedIn) {
                Text(
                    "Accedi per salvare le ore online, vederle dal sito e ricevere il report PDF via email.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = {
                        runCloud(scope, { busy = it }, toast) {
                            Cloud.signIn(context, email, password)
                            Cloud.sync(context)
                            "Accesso eseguito, ore sincronizzate"
                        }
                    }) { Text("Accedi") }
                    OutlinedButton(enabled = !busy, onClick = {
                        runCloud(scope, { busy = it }, toast) {
                            if (Cloud.signUp(context, email, password)) {
                                Cloud.sync(context)
                                "Account creato e collegato"
                            } else {
                                "Account creato: conferma l'email che ti è arrivata, poi premi Accedi"
                            }
                        }
                    }) { Text("Registrati") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else {
                Text("Collegato come ${st.email}")
                val last = if (st.lastSync > 0) {
                    Instant.ofEpochMilli(st.lastSync).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                } else "mai"
                Text("Ultima sincronizzazione: $last", style = MaterialTheme.typography.bodySmall)
                st.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        runCloud(scope, { busy = it }, toast) {
                            Cloud.sync(context)
                            "Sincronizzato"
                        }
                    }) { Text("Sincronizza ora") }
                    TextButton(onClick = { Cloud.signOut(context) }) { Text("Esci") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
                Text(
                    "Il 1° di ogni mese ricevi via email il report PDF del mese precedente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderCard(prefs: Prefs, requestNotifications: () -> Unit, requestExactAlarms: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var minute by remember { mutableIntStateOf(prefs.minute) }
    var days by remember { mutableStateOf(prefs.days) }
    var fullDay by remember { mutableStateOf(n(prefs.fullDayHours)) }
    var skip by remember { mutableStateOf(prefs.skipIfLogged) }

    fun reschedule() = ReminderScheduler.scheduleNext(context)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Promemoria giornaliero", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; prefs.enabled = it; reschedule()
                })
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        TimePickerDialog(context, { _, h, m ->
                            hour = h; minute = m
                            prefs.hour = h; prefs.minute = m
                            reschedule()
                        }, hour, minute, true).show()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Orario", modifier = Modifier.weight(1f))
                Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.titleLarge)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val labels = listOf("L", "M", "M", "G", "V", "S", "D")
                DayOfWeek.entries.forEachIndexed { i, d ->
                    FilterChip(
                        selected = d in days,
                        onClick = {
                            days = if (d in days) days - d else days + d
                            prefs.days = days
                            reschedule()
                        },
                        label = { Text(labels[i]) }
                    )
                }
            }

            OutlinedTextField(
                value = fullDay,
                onValueChange = {
                    fullDay = it
                    it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v in 0.5..24.0 }
                        ?.let { v -> prefs.fullDayHours = v }
                },
                label = { Text("Ore del pulsante rapido") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Salta se ho già segnato le ore", modifier = Modifier.weight(1f))
                Switch(checked = skip, onCheckedChange = { skip = it; prefs.skipIfLogged = it })
            }

            if (!Notifications.canPost(context)) {
                Warning("Le notifiche sono disattivate: il promemoria non può arrivare.", "Attiva", requestNotifications)
            }
            if (!ReminderScheduler.canScheduleExact(context)) {
                Warning(
                    "Senza il permesso \"Sveglie e promemoria\" la notifica può arrivare con qualche minuto di ritardo.",
                    "Concedi", requestExactAlarms
                )
            }

            OutlinedButton(onClick = { Notifications.showReminder(context, LocalDate.now()) }) {
                Text("Invia una notifica di prova")
            }
        }
    }
}

@Composable
private fun Warning(text: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun EditDialog(date: LocalDate, initial: Entry?, fullDay: Double, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var hoursText by remember { mutableStateOf(initial?.let { n(it.hours) } ?: "") }
    var travelText by remember { mutableStateOf(initial?.travel?.takeIf { it > 0 }?.let { n(it) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.format(rowDateFmt).cap()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it; error = null },
                    label = { Text("Ore lavorate") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = travelText,
                    onValueChange = { travelText = it; error = null },
                    label = { Text("Ore di viaggio") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (es. cantiere, cliente)") },
                    singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = HoursParser.parse(hoursText, fullDay)
                val t = parseTravel(travelText, fullDay)
                when {
                    h == null -> error = "Ore non valide"
                    t == null -> error = "Ore di viaggio non valide"
                    else -> {
                        HoursRepository.save(date, h, t, note.ifBlank { null })
                        onSaved()
                        onDismiss()
                    }
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
