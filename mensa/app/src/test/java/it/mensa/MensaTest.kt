package it.mensa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MensaTest {
    private val html = """
        <label for="8_3_5139_14_2193_Primo" id="lbl8_3_5139_14_2193_Primo" class="tdDescrizione tooltip-f" dpiatto="FUSILLI  AL TONNO E OLIVE" kcal="547"><span></span>&nbsp;FUSILLI</label>
        <input id='qta8_3_5139_14_2193_Primo' type=hidden value='' class="txtQta" alt='R'>
        <label id="lbl8_3_5139_14_2113_Primo" dpiatto="FUSILLI ALL&#39; OLIO">x</label>
        <input id='qta8_3_5139_14_2113_Primo' type=hidden value='' alt='R'>
        <input type="checkbox" class="txtcursore" id="8_3_5139_15_9_Secondo" name="8_3_5139_15_9_Secondo" checked />
        <label id="lbl8_3_5139_15_9_Secondo" dpiatto="FETTINA DI TACCHINO AI FERRI">x</label>
        <input id='qta8_3_5139_15_9_Secondo' type=hidden value='' alt='R'>
        <label id="lbl8_3_5139_16_117_Contorno" dpiatto="PATATE PREZZEMOLATE">x</label>
        <input id='qta8_3_5139_16_117_Contorno' type=hidden value='1' alt='R'>
        <label id="lbl8_3_5139_16_237_Contorno" dpiatto="INSALATA E RADICCHIO (c)">x</label>
        <input id='qta8_3_5139_16_237_Contorno' type=hidden value='' alt='R'>
        <input name="txtIdGruppoFatturazione" type="text" value="2" id="txtIdGruppoFatturazione" />
        <input name="txtEsistePrenotazione" type="text" value="N" id="txtEsistePrenotazione" value="N" />
    """.trimIndent()

    private val piatti = MenuParser.parsePiatti(html)

    @Test fun leggeIlMenu() {
        assertEquals(5, piatti.size)
        assertEquals("FUSILLI AL TONNO E OLIVE", piatti[0].nome)
        assertEquals("FUSILLI ALL' OLIO", piatti[1].nome)
        assertEquals("Secondo", piatti[2].categoria)
        assertTrue(piatti[2].prenotato)
        assertTrue(piatti[3].prenotato)
        assertEquals(listOf(1, 2, 3, 4, 5), piatti.map { it.n })
        assertEquals("2", MenuParser.inputValueById(html, "txtIdGruppoFatturazione"))
        assertEquals("N", MenuParser.inputValueById(html, "txtEsistePrenotazione"))
    }

    private fun n(t: String) = Scelta.interpreta(t, piatti).map { it.n }

    @Test fun numeri() {
        assertEquals(listOf(1, 4), n("1, 4"))
        assertEquals(listOf(1, 4), n("Uno e quattro"))
        assertEquals(listOf(2, 3, 5), n("due tre cinque"))
        assertEquals(listOf(1), n("1 1"))
    }

    @Test fun nomi() {
        assertEquals(listOf(1, 4), n("fusilli al tonno, patate"))
        assertEquals(listOf(3, 4), n("fettina e patate"))
        assertEquals(listOf(5), n("insalata"))
        assertEquals(emptyList<Int>(), n("boh"))
    }

    @Test fun comandi() {
        assertEquals(Scelta.Comando.MENU, Scelta.comando("Menu"))
        assertEquals(Scelta.Comando.MENU, Scelta.comando("ripeti"))
        assertEquals(Scelta.Comando.ORDINE, Scelta.comando("Cosa ho ordinato?"))
        assertEquals(null, Scelta.comando("uno e tre"))
    }

    @Test fun giornoLavorativo() {
        val giovedi = LocalDate.of(2026, 11, 26)
        assertEquals(LocalDate.of(2026, 11, 27), ItChef.giornoDaPrenotare(1, giovedi))
        assertEquals(LocalDate.of(2026, 11, 30), ItChef.giornoDaPrenotare(2, giovedi))
        assertEquals(LocalDate.of(2026, 11, 30), ItChef.giornoDaPrenotare(1, LocalDate.of(2026, 11, 27)))
    }

    @Test fun testoMenu() {
        val m = Menu(LocalDate.of(2026, 11, 27), piatti, "u", emptyMap())
        val msg = Scelta.messaggiMenu(m)
        assertTrue(msg[1].startsWith("Primi: 1, Fusilli al tonno e olive"))
        assertTrue(msg.last().contains("Hai già ordinato"))
    }
}
