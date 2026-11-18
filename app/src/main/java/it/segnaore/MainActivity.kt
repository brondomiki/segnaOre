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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.LocalTime
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

private val rowDateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ITALIAN)
private val shortDateFmt = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ITALIAN)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
private val weekdayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)
private fun n(v: Double) = HoursParser.formatNumber(v)
private fun String.cap() = replaceFirstChar { it.uppercase() }

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d ->
        onPicked(LocalDate.of(y, m + 1, d))
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun parseTravel(text: String, fullDay: Double): Double? =
    if (text.isBlank()) 0.0 else HoursParser.parse(text, fullDay)

private fun dayColor(date: LocalDate): Color = when {
    date.dayOfWeek == DayOfWeek.SUNDAY || WorkCalc.isHoliday(date) -> Palette.Holiday
    date.dayOfWeek == DayOfWeek.SATURDAY -> Palette.Overtime
    else -> Palette.Ordinary
}

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

// ---------- piccoli componenti ----------

@Composable
private fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = CircleShape) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// ---------- schermata ----------

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
    val todayEntry = entries.firstOrNull { it.date == LocalDate.now() }
    val monthSummary = WorkCalc.summarize(entries.filter { YearMonth.from(it.date) == thisMonth })

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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { HeroCard(todayEntry, monthSummary) }
            item {
                TodayCard(
                    entry = todayEntry,
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
                    }
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SectionTitle(Icons.Default.List, "Giorni (${filtered.size})")
                    }
                    TextButton(onClick = { pickDate(context, LocalDate.now()) { editing = it } }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Altro giorno")
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🗓️", fontSize = 36.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Nessuna ora in questo periodo",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
fun HeroCard(today: Entry?, month: WorkCalc.Summary) {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..12 -> "Buongiorno ☀️"
        in 13..17 -> "Buon pomeriggio 👋"
        else -> "Buonasera 🌙"
    }
    val brush = if (isSystemInDarkTheme()) Palette.heroDark else Palette.heroLight
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).cap()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(greeting, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(date, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text("Oggi", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            if (today != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(n(today.hours), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (today.hours == 1.0) "ora" else "ore",
                        color = Color.White, fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    if (today.travel > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "+ ${n(today.travel)} viaggio 🚗",
                            color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            } else {
                Text(
                    "Non hai ancora segnato le ore ✍️",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroStat("Mese", n(month.worked))
                    HeroStat("Straord.", n(month.overtime))
                    HeroStat("Festive", n(month.holiday))
                    HeroStat("Viaggio", n(month.travel))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(Icons.Default.Check, if (entry == null) "Segna le ore di oggi" else "Modifica le ore di oggi")
            if (entry != null) {
                val s = WorkCalc.split(today, entry.hours)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.ordinary > 0) Pill("Ordinarie ${n(s.ordinary)}", Palette.Ordinary)
                    if (s.overtime > 0) Pill("Straordinarie ${n(s.overtime)}", Palette.Overtime)
                    if (s.holiday > 0) Pill("Festive ${n(s.holiday)}", Palette.Holiday)
                    if (entry.travel > 0) Pill("Viaggio ${n(entry.travel)}", Palette.Travel)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it; error = null },
                    label = { Text("Ore lavorate") },
                    placeholder = { Text("es. 8") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = travelText,
                    onValueChange = { travelText = it; error = null },
                    label = { Text("Viaggio 🚗") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(0.8f)
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val h = HoursParser.parse(hoursText, fullDay)
                        val t = parseTravel(travelText, fullDay)
                        when {
                            h == null -> error = "Ore non valide"
                            t == null -> error = "Ore di viaggio non valide"
                            else -> save(h, t, hoursText)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Salva", fontWeight = FontWeight.Bold) }
                FilledTonalButton(
                    onClick = { save(fullDay, entry?.travel ?: 0.0, null) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Palette.Ordinary.copy(alpha = 0.14f), contentColor = Palette.Ordinary
                    )
                ) { Text("${n(fullDay)} ore") }
                FilledTonalButton(
                    onClick = { save(0.0, 0.0, "ferie") },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Palette.Travel.copy(alpha = 0.14f), contentColor = Palette.Travel
                    )
                ) { Text("Ferie 🏖️") }
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
    onEmail: () -> Unit
) {
    val context = LocalContext.current
    val s = WorkCalc.summarize(list)
    val now = YearMonth.now()
    val prev = now.minusMonths(1)
    val isMonth = from == now.atDay(1) && to == now.atEndOfMonth()
    val isPrev = from == prev.atDay(1) && to == prev.atEndOfMonth()
    val isYear = from == LocalDate.of(now.year, 1, 1) && to == LocalDate.of(now.year, 12, 31)

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(Icons.Default.DateRange, "Periodo e totali", Palette.Days)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeChip("Questo mese", isMonth) { onRange(now.atDay(1), now.atEndOfMonth()) }
                RangeChip("Mese scorso", isPrev) { onRange(prev.atDay(1), prev.atEndOfMonth()) }
                RangeChip("Anno", isYear) { onRange(LocalDate.of(now.year, 1, 1), LocalDate.of(now.year, 12, 31)) }
            }
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Giorni", s.days.toString(), Palette.Days)
                StatTile("Lavorate", n(s.worked), Palette.Worked)
                StatTile("Ordinarie", n(s.ordinary), Palette.Ordinary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Straord.", n(s.overtime), Palette.Overtime)
                StatTile("Festive", n(s.holiday), Palette.Holiday)
                StatTile("Viaggio", n(s.travel), Palette.Travel)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton("CSV", Icons.Default.Share, Palette.Travel, true, Modifier.weight(1f), onCsv)
                ActionButton("PDF", Icons.Default.Star, Palette.Holiday, !busy, Modifier.weight(1f), onPdf)
                ActionButton("Email", Icons.Default.Email, Palette.Ordinary, !busy, Modifier.weight(1f), onEmail)
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Un attimo…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Palette.Days.copy(alpha = 0.18f),
            selectedLabelColor = Palette.Days
        )
    )
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.14f), contentColor = color
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryRow(entry: Entry, onClick: () -> Unit, onDelete: () -> Unit) {
    val s = WorkCalc.split(entry.date, entry.hours)
    val c = dayColor(entry.date)
    AppCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(entry.date.dayOfMonth.toString(), color = c, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(entry.date.format(weekdayFmt), color = c, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (entry.hours == 0.0) Pill("Riposo / ferie", Palette.Travel)
                    if (s.ordinary > 0) Pill("ord. ${n(s.ordinary)}", Palette.Ordinary)
                    if (s.overtime > 0) Pill("straord. ${n(s.overtime)}", Palette.Overtime)
                    if (s.holiday > 0) Pill("festive ${n(s.holiday)}", Palette.Holiday)
                    if (entry.travel > 0) Pill("🚗 ${n(entry.travel)}", Palette.Travel)
                }
                if (!entry.note.isNullOrBlank()) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(n(entry.hours), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("ore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, contentDescription = "Elimina",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun ArchiveCard(entries: List<Entry>, onMonth: (YearMonth) -> Unit) {
    val months = entries.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it })
    if (months.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.Star, "Archivio mensile", Palette.Overtime)
            months.forEach { (ym, list) ->
                val s = WorkCalc.summarize(list)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onMonth(ym) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ym.format(monthFmt).cap(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${s.days} giorni · straord. ${n(s.overtime)} · festive ${n(s.holiday)} · viaggio ${n(s.travel)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("${n(s.worked)} h", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    // barra colorata: ordinarie / straordinarie / festive
                    val total = s.ordinary + s.overtime + s.holiday
                    if (total > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                        ) {
                            if (s.ordinary > 0) Box(Modifier.weight(s.ordinary.toFloat()).fillMaxSize().background(Palette.Ordinary))
                            if (s.overtime > 0) Box(Modifier.weight(s.overtime.toFloat()).fillMaxSize().background(Palette.Overtime))
                            if (s.holiday > 0) Box(Modifier.weight(s.holiday.toFloat()).fillMaxSize().background(Palette.Holiday))
                        }
                    }
                }
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
    var showPw by remember { mutableStateOf(false) }
    var newPw by remember { mutableStateOf("") }

    if (showPw) {
        var newPw2 by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        var pwError by remember { mutableStateOf<String?>(null) }
        val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()
        AlertDialog(
            onDismissRequest = { showPw = false },
            title = { Text("Cambia password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPw, onValueChange = { newPw = it; pwError = null },
                        label = { Text("Nuova password (min. 6)") }, singleLine = true,
                        visualTransformation = transform,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    OutlinedTextField(
                        value = newPw2, onValueChange = { newPw2 = it; pwError = null },
                        label = { Text("Ripeti la password") }, singleLine = true,
                        visualTransformation = transform,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = visible, onCheckedChange = { visible = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Mostra password")
                    }
                    pwError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pw = newPw
                    when {
                        pw.length < 6 -> pwError = "Almeno 6 caratteri"
                        pw != newPw2 -> pwError = "Le due password non coincidono"
                        else -> {
                            showPw = false
                            newPw = ""
                            runCloud(scope, { busy = it }, toast) {
                                Cloud.changePassword(context, pw)
                                "Password cambiata"
                            }
                        }
                    }
                }) { Text("Cambia") }
            },
            dismissButton = { TextButton(onClick = { showPw = false; newPw = "" }) { Text("Annulla") } }
        )
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(Icons.Default.AccountCircle, "Account e sito web", Palette.Travel)
            if (!st.loggedIn) {
                Text(
                    "Accedi per salvare le ore online, vederle dal sito e ricevere il report PDF via email.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                var showLoginPw by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (showLoginPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showLoginPw = !showLoginPw }) { Text(if (showLoginPw) "Nascondi" else "Mostra") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = {
                        runCloud(scope, { busy = it }, toast) {
                            Cloud.signIn(context, email, password)
                            Cloud.sync(context)
                            "Accesso eseguito, ore sincronizzate ✅"
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
                Surface(color = Palette.Travel.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✅", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Collegato come ${st.email}", fontWeight = FontWeight.SemiBold)
                            val last = if (st.lastSync > 0) {
                                Instant.ofEpochMilli(st.lastSync).atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                            } else "mai"
                            Text("Ultima sincronizzazione: $last", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                st.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        runCloud(scope, { busy = it }, toast) {
                            Cloud.sync(context)
                            "Sincronizzato ✅"
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sincronizza")
                    }
                    TextButton(onClick = { showPw = true }) { Text("Password") }
                    TextButton(onClick = { Cloud.signOut(context) }) { Text("Esci") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
                Text(
                    "📧 Il 1° di ogni mese ricevi via email il report PDF del mese precedente.",
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

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SectionTitle(Icons.Default.Notifications, "Promemoria", Palette.Overtime)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; prefs.enabled = it; reschedule()
                })
            }

            Surface(
                color = Palette.Overtime.copy(alpha = 0.10f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        TimePickerDialog(context, { _, h, m ->
                            hour = h; minute = m
                            prefs.hour = h; prefs.minute = m
                            reschedule()
                        }, hour, minute, true).show()
                    }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⏰  Ogni giorno alle", modifier = Modifier.weight(1f))
                    Text(
                        "%02d:%02d".format(hour, minute),
                        color = Palette.Overtime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = listOf("L", "M", "M", "G", "V", "S", "D")
                DayOfWeek.entries.forEachIndexed { i, d ->
                    val sel = d in days
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (sel) Palette.Overtime else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                days = if (sel) days - d else days + d
                                prefs.days = days
                                reschedule()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            labels[i],
                            color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                shape = RoundedCornerShape(14.dp),
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

            OutlinedButton(
                onClick = { Notifications.showReminder(context, LocalDate.now()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔔  Invia una notifica di prova") }
        }
    }
}

@Composable
private fun Warning(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
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
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = travelText,
                    onValueChange = { travelText = it; error = null },
                    label = { Text("Ore di viaggio 🚗") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (es. cantiere, cliente)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
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
