package it.segnaore

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HoursRepository.init(this)
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
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(requestNotifications: () -> Unit, requestExactAlarms: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val entries by HoursRepository.entries.collectAsStateWithLifecycle()

    // Forza il ricalcolo dello stato dei permessi quando si torna nell'app
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    var editing by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("SegnaOre") }) }) { padding ->
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
                    fullDay = prefs.fullDayHours
                )
            }
            item {
                key(resumeTick) {
                    ReminderCard(prefs, requestNotifications, requestExactAlarms)
                }
            }
            item { MonthCard(entries, onPickDay = { editing = it }) }
            if (entries.isNotEmpty()) {
                item {
                    Text(
                        "Storico",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            items(entries, key = { it.date.toString() }) { e ->
                EntryRow(e, onClick = { editing = e.date }, onDelete = { HoursRepository.delete(e.date) })
            }
        }
    }

    editing?.let { date ->
        EditDialog(
            date = date,
            initial = HoursRepository.get(date)?.hours,
            fullDay = prefs.fullDayHours,
            onDismiss = { editing = null }
        )
    }
}

@Composable
fun TodayCard(entry: Entry?, fullDay: Double) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    fun save(hours: Double, note: String?) {
        HoursRepository.save(today, hours, note)
        text = ""
        error = false
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
                    .replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                entry?.let { HoursParser.format(it.hours) } ?: "Ore non ancora segnate",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = { Text("Ore (es. 8, 7,5, sette e mezza)") },
                isError = error,
                supportingText = if (error) {
                    { Text("Non riesco a interpretarlo") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val h = HoursParser.parse(text, fullDay)
                    if (h == null) error = true else save(h, text)
                }) { Text("Salva") }
                AssistChip(onClick = { save(fullDay, null) }, label = { Text(HoursParser.formatNumber(fullDay)) })
                AssistChip(onClick = { save(fullDay / 2, null) }, label = { Text(HoursParser.formatNumber(fullDay / 2)) })
                AssistChip(onClick = { save(0.0, "ferie") }, label = { Text("Ferie") })
            }
        }
    }
}

@Composable
fun ReminderCard(prefs: Prefs, requestNotifications: () -> Unit, requestExactAlarms: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var minute by remember { mutableIntStateOf(prefs.minute) }
    var days by remember { mutableStateOf(prefs.days) }
    var fullDay by remember { mutableStateOf(HoursParser.formatNumber(prefs.fullDayHours)) }
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

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                label = { Text("Ore di una giornata piena") },
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
fun MonthCard(entries: List<Entry>, onPickDay: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val month = YearMonth.now()
    val inMonth = entries.filter { YearMonth.from(it.date) == month }
    val total = inMonth.sumOf { it.hours }

    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(month.format(monthFmt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${HoursParser.format(total)} in ${inMonth.size} giorni",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = {
                val t = LocalDate.now()
                DatePickerDialog(context, { _, y, m, d ->
                    onPickDay(LocalDate.of(y, m + 1, d))
                }, t.year, t.monthValue - 1, t.dayOfMonth).show()
            }) { Text("Altro giorno") }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Ore lavorate")
                    .putExtra(Intent.EXTRA_TEXT, HoursRepository.toCsv())
                context.startActivity(Intent.createChooser(send, "Esporta ore (CSV)"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Esporta CSV")
            }
        }
    }
}

@Composable
fun EntryRow(entry: Entry, onClick: () -> Unit, onDelete: () -> Unit) {
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
fun EditDialog(date: LocalDate, initial: Double?, fullDay: Double, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial?.let { HoursParser.formatNumber(it) } ?: "") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.format(rowDateFmt)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = { Text("Ore") },
                isError = error,
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val h = HoursParser.parse(text, fullDay)
                if (h == null) error = true else {
                    HoursRepository.save(date, h, text)
                    onDismiss()
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
