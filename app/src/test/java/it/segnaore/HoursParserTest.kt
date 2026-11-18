package it.segnaore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoursParserTest {
    private fun p(s: String) = HoursParser.parse(s, 8.0)

    @Test fun cases() {
        val expected = mapOf(
            "8" to 8.0, "8 ore" to 8.0, "otto" to 8.0, "Otto ore" to 8.0,
            "7,5" to 7.5, "7.5" to 7.5, "7:30" to 7.5, "7 e 30" to 7.5,
            "sette e mezza" to 7.5, "sette ore e mezza" to 7.5, "otto e un quarto" to 8.25,
            "sette e tre quarti" to 7.75, "7 e 45" to 7.75,
            "sette ore e trenta minuti" to 7.5, "7 ore 30 minuti" to 7.5,
            "mezz'ora" to 0.5, "un'ora e mezza" to 1.5, "45 minuti" to 0.75,
            "mezza giornata" to 4.0, "giornata intera" to 8.0, "ferie" to 0.0,
            "zero" to 0.0, "ho lavorato nove ore" to 9.0, "dieci e mezzo" to 10.5,
            "ventitré" to 23.0
        )
        expected.forEach { (input, hours) -> assertEquals(input, hours, p(input)!!, 0.001) }
    }

    @Test fun invalid() {
        assertNull(p(""))
        assertNull(p("boh"))
        assertNull(p("30"))
    }

    @Test fun travel() {
        val cases = listOf(
            "otto e due di viaggio" to HoursParser.Parsed(8.0, 2.0),
            "8 ore, viaggio 2" to HoursParser.Parsed(8.0, 2.0),
            "otto ore e due ore e mezza di trasferta" to HoursParser.Parsed(8.0, 2.5),
            "otto e mezza e un'ora di viaggio" to HoursParser.Parsed(8.5, 1.0),
            "solo viaggio tre ore" to HoursParser.Parsed(null, 3.0),
            "nove ore più due di viaggio" to HoursParser.Parsed(9.0, 2.0),
            "sette e trenta e un'ora e mezza di viaggio" to HoursParser.Parsed(7.5, 1.5),
            "10 ore lavoro 3 ore viaggio" to HoursParser.Parsed(10.0, 3.0),
            "otto" to HoursParser.Parsed(8.0, null)
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, HoursParser.parseFull(input)) }
    }

    @Test fun commands() {
        assertEquals(HoursParser.Command.REPORT, HoursParser.command("invia il report"))
        assertEquals(HoursParser.Command.SUMMARY, HoursParser.command("riepilogo del mese"))
        assertEquals(HoursParser.Command.DELETE, HoursParser.command("cancella"))
        assertNull(HoursParser.command("otto ore"))
    }

    @Test fun overtime() {
        // mercoledì 18/11/2026
        assertEquals(WorkCalc.Split(8.0, 2.0, 0.0), WorkCalc.split(java.time.LocalDate.of(2026, 11, 18), 10.0))
        // sabato
        assertEquals(WorkCalc.Split(0.0, 5.0, 0.0), WorkCalc.split(java.time.LocalDate.of(2026, 11, 21), 5.0))
        // domenica
        assertEquals(WorkCalc.Split(0.0, 0.0, 4.0), WorkCalc.split(java.time.LocalDate.of(2026, 11, 22), 4.0))
        // Pasquetta 2026 = 6 aprile
        assertEquals(true, WorkCalc.isHoliday(java.time.LocalDate.of(2026, 4, 6)))
    }
}
