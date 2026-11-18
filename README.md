# SegnaOre

App Android che ogni giorno, a un orario stabilito, ti chiede quante ore hai lavorato.
Puoi rispondere dalla notifica sul telefono oppure **a voce da Android Auto**, senza toccare lo schermo.

## Novità versione 1.1

- **Ore di viaggio/trasferta** in un campo separato (totale a parte, non conta per gli straordinari).
- **Straordinari automatici**: lun-ven oltre 8 ore = straordinario; sabato tutto straordinario; domenica e festivi nazionali (Pasquetta compresa) = straordinario festivo.
- **Filtro per periodo** (dal/al, questo mese, mese scorso, anno) con totali.
- **Archivio mensile** con la somma di ogni mese.
- **Export CSV** (si apre con Excel), **PDF** e **invio del report via email**.
- **Account online** (Supabase): le ore si sincronizzano tra telefono e sito web.
- **Report automatico**: il 1° di ogni mese alle 7:00 arriva a brondomiki@gmail.com il PDF del mese precedente.
- **Comandi vocali** (anche da Android Auto): "otto e due di viaggio", "riepilogo", "invia report", "cancella".

## Sito web (Vercel)

La cartella `web/` è il sito: stessa base dati dell'app, con login, inserimento (anche con microfono), filtro, CSV, PDF ed email.
Per pubblicarlo: vercel.com → Add New → Project → importa `brondomiki/segnaOre` → **Root Directory: `web`** → Framework: Other → Deploy.

## Backend (Supabase, progetto "portafoglio")

- Tabelle `segnaore_entries` e `segnaore_settings` con sicurezza per utente (RLS).
- Funzione `segnaore-report` (`supabase/functions/`): genera il PDF e lo invia con Resend.
- Job `segnaore-report-mensile` (pg_cron) il 1° del mese alle 06:00 UTC.
- Segreto necessario: `RESEND_API_KEY` (Edge Functions → Secrets).

## Come funziona

- **Promemoria**: arriva all'orario scelto (default 18:00, da lunedì a venerdì). Se hai già segnato le ore della giornata non arriva (puoi disattivare questa opzione).
- **Notifica**: ha tre pulsanti: *Rispondi* (scrivi o detta), un pulsante rapido con la giornata piena (es. "8 ore") e *Più tardi* (te lo richiede dopo 30 minuti).
- **Android Auto**: la notifica è in formato "messaggio". In auto l'assistente la legge ("SegnaOre: quante ore hai lavorato oggi?"), tu dici *"Rispondi"* e poi il numero di ore. L'app conferma a voce ("Fatto: 8 ore segnate per oggi") oppure ti chiede di ripetere se non ha capito.
- **Cosa puoi dire**: "otto", "8 ore", "sette e mezza", "otto e un quarto", "sette e tre quarti", "7 e 45", "sette ore e trenta minuti", "mezz'ora", "mezza giornata", "giornata intera", "ferie", "zero".
- **Nell'app**: inserisci o correggi le ore di oggi, aggiungi un giorno passato ("Altro giorno"), vedi il totale del mese e lo storico. Tocca una riga per modificarla. Con l'icona di condivisione esporti tutto in CSV (`data;ore;nota`).

I dati restano solo sul telefono (database SQLite locale).

## Compilare e installare

1. Apri la cartella `SegnaOre` con **Android Studio** (Ladybug o più recente) e attendi la sincronizzazione Gradle.
2. Collega il telefono con il debug USB attivo e premi **Run**. In alternativa: *Build → Build APK(s)* e installa l'APK.
3. Al primo avvio concedi il permesso per le **notifiche**.
4. Se l'app lo chiede, concedi **"Sveglie e promemoria"**: serve perché la notifica arrivi all'orario esatto.
5. Premi **"Invia una notifica di prova"** per controllare che tutto funzioni.

Requisiti: Android 8.0 o superiore.

## Attivare Android Auto (app installata fuori dal Play Store)

Android Auto mostra le notifiche solo delle app scaricate dal Play Store, a meno che non attivi le fonti sconosciute:

1. Sul telefono apri **Impostazioni → Android Auto** (oppure l'app Android Auto).
2. Scorri fino in fondo e tocca **Versione** circa 10 volte, poi conferma per attivare le impostazioni sviluppatore.
3. Dal menu ⋮ apri **Impostazioni sviluppatore** e attiva **Fonti sconosciute** (Unknown sources).
4. Nelle impostazioni di Android Auto controlla che le **notifiche dei messaggi** siano attive.

Per provarla senza auto puoi usare il **Desktop Head Unit (DHU)** dell'SDK Android.

## Batteria

Su alcuni telefoni (Samsung, Xiaomi, Huawei…) il risparmio energetico può bloccare i promemoria.
Se non arrivano: *Impostazioni → App → SegnaOre → Batteria → Senza restrizioni*.

## Struttura del codice

| File | Ruolo |
|---|---|
| `ReminderScheduler.kt` | Programma la sveglia giornaliera e il rinvio (AlarmManager) |
| `ReminderReceiver.kt` | Scatta all'orario e mostra la notifica |
| `Notifications.kt` | Notifica in stile messaggio compatibile con Android Auto (risposta + "letto") |
| `ActionReceiver.kt` | Gestisce risposta vocale o scritta, pulsante rapido e rinvio |
| `HoursParser.kt` | Trasforma frasi come "sette e mezza" in 7,5 ore |
| `HoursRepository.kt` | Archivio SQLite, un valore per giorno |
| `BootReceiver.kt` | Riprogramma il promemoria dopo riavvio o cambio d'ora |
| `MainActivity.kt` | Interfaccia (Jetpack Compose) |

Test del riconoscimento: `./gradlew test` (oppure tasto destro su `HoursParserTest` → Run).

## Nota sul Play Store

Per pubblicarla sul Play Store, Google limita le app per Android Auto a categorie precise (messaggi, media, navigazione…). Un'app per segnare le ore che usa le notifiche-messaggio potrebbe non superare la revisione. Per uso personale (installazione diretta) funziona senza problemi.
