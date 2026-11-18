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
}
