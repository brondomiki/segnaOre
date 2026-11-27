# Mensa

App Android che ogni giorno lavorativo legge il menu della mensa Camst (portale ITChef, cucina di Quarto d'Altino) e te lo manda come messaggio.
In auto **Android Auto** te lo legge e tu rispondi a voce: l'app fa l'ordine sul portale.

## Installazione

1. Dal telefono apri **github.com/brondomiki/segnaOre/releases**, cerca l'ultima release **"Mensa build …"** e scarica `Mensa.apk`.
2. Installalo e consenti l'installazione da fonti sconosciute se te lo chiede.
3. Apri l'app, inserisci utente e password ITChef, scegli l'orario e premi **Salva**.
4. Premi **"Mandami il menu adesso"** per provare.

Android Auto: stesse impostazioni di SegnaOre (Fonti sconosciute nelle impostazioni sviluppatore di Android Auto).

Fino alle 8:30 il menu e l'ordine sono per oggi, dopo per il giorno lavorativo successivo.
Toccando la notifica si apre l'app con il menu completo e le caselle per ordinare a mano.

## Cosa puoi rispondere

- numeri: "uno, undici e ventuno"
- nomi: "fusilli al tonno, fettina e patate"
- "menu" per riascoltarlo, "cosa ho ordinato"
- "aggiungi ventuno" per aggiungere un piatto a quelli già ordinati

Per cambiare l'ordine rispondi di nuovo con altri piatti: la nuova scelta sostituisce la precedente.

## Come funziona

| File | Ruolo |
|---|---|
| `ItChef.kt` | Login al portale, lettura del menu, prenotazione (`ITChefWS.asmx/GestionePrenotazioneMenuGiornoWS`, prima in modalità controllo e poi salvataggio) |
| `Scelta.kt` | Capisce la risposta (numeri in cifre o parole, nomi dei piatti) e prepara i testi |
| `Notifications.kt` | Notifica in stile messaggio compatibile con Android Auto |
| `Mensa.kt` | Sveglia giornaliera, ricevitori, operazioni |
| `MainActivity.kt` | Impostazioni |

La password resta salvata solo sul telefono, nella memoria privata dell'app.
Gli ID del profilo (cliente 256, mensa 542, utente 5139…) sono in `ItChef.IDS`.
