package it.segnaore

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.round

/**
 * Interpreta il testo dettato (o scritto) e lo converte in ore.
 *
 * Esempi riconosciuti: "8", "8 ore", "otto", "7,5", "7.5", "7:30", "sette e mezza",
 * "otto e un quarto", "sette e tre quarti", "7 e 45", "sette ore e trenta minuti",
 * "mezz'ora", "45 minuti", "mezza giornata", "giornata intera", "ferie", "niente".
 */
object HoursParser {

    private val numberWords = mapOf(
        "zero" to 0, "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3,
        "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9,
        "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13, "quattordici" to 14,
        "quindici" to 15, "sedici" to 16, "diciassette" to 17, "diciotto" to 18,
        "diciannove" to 19, "venti" to 20, "ventuno" to 21, "ventidue" to 22,
        "ventitre" to 23, "ventiquattro" to 24, "trenta" to 30, "quaranta" to 40,
        "quarantacinque" to 45, "cinquanta" to 50
    )

    private val zeroWords = listOf(
        "ferie", "festa", "festivo", "niente", "nulla", "nessuna", "malattia", "riposo"
    )

    private val hourWords = setOf("ore", "ora", "h")
    private val minuteWords = setOf("minuti", "minuto", "min", "m")

    private sealed interface Tok {
        data class Num(val value: Double, val hasFraction: Boolean) : Tok
        data class Add(val value: Double) : Tok
        data class Word(val text: String) : Tok
    }

    fun parse(input: String, fullDayHours: Double = 8.0): Double? {
        var t = input.lowercase(Locale.ITALIAN)
            .replace('’', '\'')
            .replace("é", "e").replace("è", "e")
            .trim()
        if (t.isEmpty()) return null

        if (zeroWords.any { Regex("\\b$it\\b").containsMatchIn(t) }) return 0.0
        if (Regex("\\bmezza giornata\\b").containsMatchIn(t)) return fullDayHours / 2
        if (Regex("\\bgiornata (intera|piena|completa)\\b").containsMatchIn(t) ||
            Regex("\\btutto il giorno\\b").containsMatchIn(t)
        ) return fullDayHours

        // Frazioni espresse a parole -> token speciali
        t = t.replace(Regex("\\be\\s+tre\\s+quarti(\\s+d'\\s*ora)?"), " +0.75 ")
            .replace(Regex("\\be\\s+un\\s+quarto(\\s+d'\\s*ora)?"), " +0.25 ")
            .replace(Regex("\\be\\s+mezz[ao](\\s+ora)?\\b"), " +0.5 ")
            .replace(Regex("\\btre\\s+quarti\\s+d'\\s*ora"), " 0.75 ")
            .replace(Regex("\\bun\\s+quarto\\s+d'\\s*ora"), " 0.25 ")
            .replace(Regex("\\bmezz'\\s*ora\\b|\\bmezza\\s+ora\\b"), " 0.5 ")
            .replace('\'', ' ')

        val tokens = Regex("\\+\\d+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?(?::\\d{1,2})?|[a-zàìòù]+")
            .findAll(t)
            .map { m -> toToken(m.value) }
            .toList()

        val firstNum = tokens.indexOfFirst { it is Tok.Num }
        if (firstNum < 0) return null

        val base = tokens[firstNum] as Tok.Num
        var hours = base.value
        var i = firstNum + 1

        // "45 minuti" da solo
        if (i < tokens.size && tokens[i].isWord(minuteWords)) {
            return valid(hours / 60.0)
        }
        // salta "ore"/"ora"/"h"
        while (i < tokens.size && tokens[i].isWord(hourWords)) i++

        if (i < tokens.size) {
            val next = tokens[i]
            when {
                next is Tok.Add -> hours += next.value

                // "7 e 30", "sette ore e trenta minuti"
                next.isWord(setOf("e")) && i + 1 < tokens.size -> {
                    when (val after = tokens[i + 1]) {
                        is Tok.Add -> hours += after.value
                        is Tok.Num -> if (!base.hasFraction && after.value in 1.0..59.0) {
                            hours += after.value / 60.0
                        }
                        else -> {}
                    }
                }

                // "7 ore 30 minuti" / "7 30"
                next is Tok.Num && !base.hasFraction && next.value in 1.0..59.0 -> {
                    hours += next.value / 60.0
                }
            }
        }
        return valid(hours)
    }

    /** Risultato con ore lavorate e ore di viaggio (null = non dette). */
    data class Parsed(val hours: Double?, val travel: Double?)

    enum class Command { SUMMARY, REPORT, DELETE }

    private val travelWord = Regex("\\b(viaggio|viaggi|trasferta|trasferte|guida)\\b")
    private val connector = Regex(",|\\s+(e|più|piu|poi|piu')\\s+")

    /**
     * Riconosce anche le ore di viaggio:
     * "otto e due di viaggio", "8 ore, viaggio 2", "otto ore e due ore e mezza di trasferta",
     * "solo viaggio tre ore".
     */
    fun parseFull(input: String, fullDayHours: Double = 8.0): Parsed? {
        val t = input.lowercase(Locale.ITALIAN).trim()
        val m = travelWord.find(t)
            ?: return parse(input, fullDayHours)?.let { Parsed(it, null) }

        val before = t.substring(0, m.range.first).trim()
        val after = t.substring(m.range.last + 1).trim()

        val travelAfter = if (after.isNotEmpty()) parse(after, fullDayHours) else null
        if (travelAfter != null) {
            return Parsed(parse(before, fullDayHours), travelAfter)
        }

        // Viaggio detto prima della parola chiave: cerca il punto in cui separare
        // lavoro e viaggio in modo che entrambe le parti abbiano senso.
        val conn = connector.findAll(before).toList()
        val endsWithHours = Regex("\\b(ore|ora|h)\\s*$")
        val splits = mutableListOf<Pair<Int, Int>>() // (fine parte lavoro, inizio parte viaggio)
        conn.filter { endsWithHours.containsMatchIn(before.substring(0, it.range.first)) }
            .forEach { splits += it.range.first to it.range.last + 1 }
        conn.reversed().forEach { splits += it.range.first to it.range.last + 1 }
        numberStart.findAll(before).drop(1).toList().reversed()
            .forEach { splits += it.range.first to it.range.first }

        for ((endWork, startTravel) in splits) {
            val work = parse(before.substring(0, endWork), fullDayHours) ?: continue
            val travel = parse(before.substring(startTravel), fullDayHours) ?: continue
            return Parsed(work, travel)
        }
        // "tre ore di viaggio" senza ore di lavoro
        val travel = parse(before, fullDayHours) ?: return null
        return Parsed(null, travel)
    }

    private val numberStart = Regex(
        "\\b(\\d+|" + listOf(
            "zero", "un", "uno", "una", "due", "tre", "quattro", "cinque", "sei", "sette",
            "otto", "nove", "dieci", "undici", "dodici", "tredici", "quattordici", "quindici",
            "sedici", "diciassette", "diciotto", "diciannove", "venti"
        ).joinToString("|") + ")\\b"
    )

    fun command(input: String): Command? {
        val t = input.lowercase(Locale.ITALIAN)
        return when {
            Regex("\\breport\\b|\\brapporto\\b").containsMatchIn(t) -> Command.REPORT
            Regex("\\b(cancella|elimina|annulla)\\b").containsMatchIn(t) -> Command.DELETE
            Regex("\\b(totale|totali|riepilogo|riassunto|straordinari|questo mese|del mese)\\b")
                .containsMatchIn(t) -> Command.SUMMARY
            else -> null
        }
    }

    private fun toToken(raw: String): Tok {
        if (raw.startsWith("+")) return Tok.Add(raw.substring(1).toDouble())
        if (raw.first().isDigit()) {
            if (raw.contains(':')) {
                val (h, m) = raw.split(':')
                return Tok.Num(h.replace(',', '.').toDouble() + m.toDouble() / 60.0, true)
            }
            val normalized = raw.replace(',', '.')
            return Tok.Num(normalized.toDouble(), normalized.contains('.'))
        }
        numberWords[raw]?.let { return Tok.Num(it.toDouble(), false) }
        return Tok.Word(raw)
    }

    private fun Tok.isWord(set: Set<String>) = this is Tok.Word && text in set

    private fun valid(h: Double): Double? {
        if (h.isNaN() || h < 0.0 || h > 24.0) return null
        return round(h * 100) / 100
    }

    private val fmt = DecimalFormat("0.##", DecimalFormatSymbols(Locale.ITALIAN))

    /** 7.5 -> "7,5 ore", 1.0 -> "1 ora" */
    fun format(hours: Double): String {
        val n = fmt.format(hours)
        return if (hours == 1.0) "$n ora" else "$n ore"
    }

    fun formatNumber(hours: Double): String = fmt.format(hours)
}
