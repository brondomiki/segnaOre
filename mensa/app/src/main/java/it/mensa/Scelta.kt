package it.mensa

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Trasforma la risposta (scritta o dettata) nei piatti scelti, e prepara i testi da leggere. */
object Scelta {
    enum class Comando { MENU, ORDINE }

    private val NUMERI = mapOf(
        "uno" to 1, "un" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11,
        "dodici" to 12, "tredici" to 13, "quattordici" to 14, "quindici" to 15, "sedici" to 16,
        "diciassette" to 17, "diciotto" to 18, "diciannove" to 19, "venti" to 20, "ventuno" to 21,
        "ventidue" to 22, "ventitre" to 23, "ventiquattro" to 24, "venticinque" to 25,
        "ventisei" to 26, "ventisette" to 27, "ventotto" to 28, "ventinove" to 29, "trenta" to 30,
        "trentuno" to 31, "trentadue" to 32, "trentatre" to 33, "trentaquattro" to 34, "trentacinque" to 35,
        "trentasei" to 36, "trentasette" to 37, "trentotto" to 38, "trentanove" to 39, "quaranta" to 40
    )

    private val STOP = setOf(
        "il", "la", "lo", "i", "gli", "le", "al", "alla", "allo", "ai", "agli", "alle", "con", "di", "da",
        "del", "della", "in", "per", "nr", "gr", "un", "una", "uno", "poi", "anche", "voglio", "prendo", "vorrei"
    )

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun comando(testo: String): Comando? {
        val t = norm(testo)
        return when {
            t.isEmpty() || t == "menu" || t.startsWith("ripeti") || t.contains("leggi il menu") ||
                t == "rileggi" || t.contains("dimmi il menu") -> Comando.MENU
            t.contains("cosa ho ordinato") || t.contains("che cosa ho ordinato") ||
                t.contains("mio ordine") || t == "ordine" || t == "riepilogo" -> Comando.ORDINE
            else -> null
        }
    }

    /** Numeri ("1, 12 e 21", "uno dodici ventuno") oppure nomi ("fusilli al tonno e patate"). */
    fun interpreta(testo: String, piatti: List<Piatto>): List<Piatto> {
        val parole = norm(testo).split(' ').filter { it.isNotEmpty() }
        val numeri = parole.mapNotNull { w -> w.toIntOrNull() ?: NUMERI[w] }
            .filter { it in 1..piatti.size }
            .distinct()
        if (numeri.isNotEmpty()) return numeri.map { piatti[it - 1] }

        val pezzi = testo.lowercase(Locale.ITALIAN)
            .split(Regex(",|;|\\.|\\s+(?:e|poi|più|piu)\\s+"))
            .map { norm(it) }
            .filter { it.isNotEmpty() }
        val scelti = ArrayList<Piatto>()
        for (pezzo in pezzi) {
            val tok = pezzo.split(' ').filter { it.length > 2 && it !in STOP }
            if (tok.isEmpty()) continue
            var best: Piatto? = null
            var bestScore = 0.0
            for (p in piatti) {
                val nome = norm(p.nome)
                val score = tok.count { nome.contains(it) }.toDouble() / tok.size
                if (score > bestScore) { best = p; bestScore = score }
            }
            if (best != null && bestScore >= 0.5 && best !in scelti) scelti += best
        }
        return scelti
    }

    private val CATEGORIE = linkedMapOf(
        "Primo" to "Primi", "Secondo" to "Secondi", "Contorno" to "Contorni", "PiattoUnico" to "Piatto unico"
    )

    fun bello(nome: String): String {
        val t = nome.lowercase(Locale.ITALIAN)
        return t.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    }

    private val giornoFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

    fun giorno(d: LocalDate, oggi: LocalDate = LocalDate.now()): String = when (d) {
        oggi -> "oggi"
        oggi.plusDays(1) -> "domani"
        else -> d.format(giornoFmt)
    }

    /**
     * Tutto il menu in un solo messaggio: così Android Auto lo legge per intero
     * e sul telefono non restano nascosti i primi.
     */
    fun testoMenu(menu: Menu): String {
        if (menu.piatti.isEmpty()) return "Non c'è nessun menu per ${giorno(menu.data)}."
        val sb = StringBuilder("Menu di ${giorno(menu.data)}.")
        for ((cat, piatti) in menu.piatti.groupBy { it.categoria }) {
            sb.append("\n").append(CATEGORIE[cat] ?: cat).append(": ")
            sb.append(piatti.joinToString("; ") { "${it.n}, ${bello(it.nome)}" + if (it.prenotato) " (ordinato)" else "" })
            sb.append(".")
        }
        val gia = menu.piatti.filter { it.prenotato }
        sb.append("\n")
        sb.append(
            if (gia.isNotEmpty()) "Hai già ordinato: ${elenco(gia)}. Rispondi con altri numeri per cambiare."
            else "Rispondi con i numeri dei piatti."
        )
        return sb.toString()
    }

    fun elenco(piatti: List<Piatto>) = piatti.joinToString(", ") { bello(it.nome) }
}
