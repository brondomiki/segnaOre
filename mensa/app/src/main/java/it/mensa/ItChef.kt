package it.mensa

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate

data class Piatto(
    val n: Int,
    val code: String,
    val categoria: String,
    val nome: String,
    val provenienza: String,
    val prenotato: Boolean
)

data class Menu(
    val data: LocalDate,
    val piatti: List<Piatto>,
    val nomeUtente: String,
    val info: Map<String, String>
)

class ItChefException(msg: String) : Exception(msg)

/** Parsing della pagina del menu: separato dalla rete così si può testare. */
object MenuParser {
    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    fun attr(tag: String, name: String): String? {
        val m = Regex("""\s$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        val v = m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: ""
        return decode(v)
    }

    fun formInputs(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val name = attr(tag, "name") ?: return@forEach
            val type = (attr(tag, "type") ?: "text").lowercase()
            if (type in setOf("checkbox", "radio", "image", "button")) return@forEach
            out[name] = attr(tag, "value") ?: ""
        }
        return out
    }

    fun inputValueById(html: String, id: String): String? {
        val m = Regex("""<input\b[^>]*\sid\s*=\s*["']${Regex.escape(id)}["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        return attr(m.value, "value") ?: ""
    }

    fun parsePiatti(html: String): List<Piatto> {
        val labels = HashMap<String, String>()
        Regex("""<label\b[^>]*\bdpiatto\s*=[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val id = attr(m.value, "id") ?: return@forEach
            if (id.startsWith("lbl")) labels[id.removePrefix("lbl")] = attr(m.value, "dpiatto") ?: ""
        }
        val checked = Regex("""<input\b[^>]*type\s*=\s*["']?checkbox[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .filter { Regex("""\schecked\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }
            .mapNotNull { attr(it.value, "id") }
            .toSet()
        val out = ArrayList<Piatto>()
        Regex("""<input\b[^>]*\bid\s*=\s*["']qta[^"']+["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val code = attr(m.value, "id")!!.removePrefix("qta")
            val qta = attr(m.value, "value")?.trim()?.toIntOrNull() ?: 0
            out += Piatto(
                n = out.size + 1,
                code = code,
                categoria = code.substringAfterLast('_'),
                nome = (labels[code] ?: code).replace(Regex("\\s+"), " ").trim(),
                provenienza = (attr(m.value, "alt") ?: "R").substringBefore('-'),
                prenotato = qta > 0 || code in checked
            )
        }
        return out
    }
}

/** Client per il portale ITChef Web di Camst (login, menu, prenotazione). */
class ItChef(private val user: String, private val pass: String) {
    companion object {
        const val BASE = "https://itchefwebcl.camst.it/quartodaltino/ITChefWebPrenotazioni/"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        // ID del tuo profilo letti dal portale (cliente, mensa, servizio pranzo, ecc.)
        val IDS = linkedMapOf(
            "IdCliente" to "256",
            "IdDestinazione" to "542",
            "IdServizio" to "2",
            "IdClasse" to "211",
            "IdUtente" to "5139",
            "IdTipologia" to "8",
            "IdDieta" to "3"
        )

        /** Giorno da prenotare: oggi + [anticipo] giorni, saltando sabato e domenica. */
        fun giornoDaPrenotare(anticipo: Int, oggi: LocalDate = LocalDate.now()): LocalDate {
            var d = oggi.plusDays(anticipo.toLong())
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            return d
        }
    }

    private val cookies = LinkedHashMap<String, String>()
    private var loggedIn = false

    private class Resp(val code: Int, val url: String, val body: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(path: String, method: String = "GET", body: String? = null, contentType: String? = null): Resp {
        var url = URL(URL(BASE), path)
        var m = method
        var b = body
        repeat(8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.requestMethod = m
            c.setRequestProperty("User-Agent", UA)
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (b != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                if (contentType?.startsWith("application/json") == true) c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                c.outputStream.use { it.write(b!!.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.forEach { sc ->
                val pair = sc.substringBefore(';')
                val i = pair.indexOf('=')
                if (i > 0) cookies[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
            val loc = c.getHeaderField("Location")
            if (code in 300..399 && loc != null) {
                c.disconnect()
                url = URL(url, loc)
                m = "GET"; b = null
                return@repeat
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            return Resp(code, url.toString(), text)
        }
        throw ItChefException("Troppi reindirizzamenti dal portale")
    }

    private fun form(map: Map<String, String>) = map.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    fun login() {
        if (user.isBlank() || pass.isBlank()) throw ItChefException("Inserisci utente e password nell'app")
        val page = request("Default.aspx")
        val fields = MenuParser.formInputs(page.body).toMutableMap()
        fields["lgLogin\$UserName"] = user
        fields["lgLogin\$Password"] = pass
        fields["lgLogin\$ConfermaPassword"] = ""
        if (fields["lgLogin\$LoginButton"].isNullOrEmpty()) fields["lgLogin\$LoginButton"] = "Entra"
        val r = request("Default.aspx", "POST", form(fields))
        if (r.url.contains("Default.aspx", true) && r.body.contains("lgLogin\$Password")) {
            throw ItChefException("Accesso a ITChef non riuscito: controlla utente e password")
        }
        loggedIn = true
    }

    fun caricaMenu(data: LocalDate): Menu {
        if (!loggedIn) login()
        val ricerca = request("portale/prenotazioni/PersonalizzataRicerca.aspx")
        if (ricerca.body.contains("lgLogin\$Password")) throw ItChefException("Sessione ITChef scaduta")
        val sessione = MenuParser.inputValueById(ricerca.body, "cphCorpo_txtCodiceSessione")
            ?: MenuParser.formInputs(ricerca.body)["ctl00\$cphCorpo\$txtCodiceSessione"] ?: ""
        val dataIso = "${data}T00:00:00"
        val params = LinkedHashMap(IDS).apply {
            put("TipoOperazione", "Modifica")
            put("TipoGestione", "Prenotazione")
            put("DataRicerca", dataIso)
            put("DescrizioneUtente", "")
            put("DescrizionesSrvizio", "Pranzo")
            put("CodiceSessione", sessione)
            put("GestioneBorsellino", "N")
            put("ModalitaRicarica", "0")
            put("StampaScontrino", "N")
            put("StampaScontrinoAutomatica", "N")
            put("StampaScontrinoConfermaPrenotazione", "N")
        }
        val html = request("portale/prenotazioni/PersonalizzataRisultato.aspx?" + form(params)).body
        fun v(id: String) = MenuParser.inputValueById(html, id) ?: ""
        fun vOr(id: String, def: String) = v(id).ifBlank { def }
        val info = linkedMapOf(
            "sCodiceSessione" to vOr("txtCodiceSessione", sessione),
            "sDataRicerca" to dataIso,
            "lIdCliente" to vOr("txtIdCliente", IDS["IdCliente"]!!),
            "lIdDestinazione" to vOr("txtIdDestinazione", IDS["IdDestinazione"]!!),
            "lIdServizio" to vOr("txtIdServizio", IDS["IdServizio"]!!),
            "lIdClasse" to vOr("txtIdClasse", IDS["IdClasse"]!!),
            "lIdUtente" to vOr("txtIdUtente", IDS["IdUtente"]!!),
            "lIdGruppoFatturazione" to vOr("txtIdGruppoFatturazione", "2"),
            "lIdTipologia" to vOr("txtIdTipologia", IDS["IdTipologia"]!!),
            "lIdDieta" to vOr("txtIdDieta", IDS["IdDieta"]!!),
            "sModificaFuoriOrario" to vOr("txtModificaFuoriOrario", "N"),
            "sOrariConsentitiUsoSitoWeb" to v("txtOrarioAccessoConsentito"),
            "sOrariBloccoUsoSitoWeb" to v("txtOrarioBloccoSito"),
            "sFestivitaPrenotabile" to vOr("txtFestivitaPrenotabile", "S")
        )
        return Menu(data, MenuParser.parsePiatti(html), vOr("txtNomeUtente", user), info)
    }

    /** Ritorna null se ok, altrimenti il messaggio di errore del portale. */
    private fun chiamaWS(menu: Menu, scelti: Set<String>, soloControllo: Boolean): String? {
        val codici = menu.piatti.joinToString(";") { "${it.code}_${if (it.code in scelti) 1 else 0}_${it.provenienza}" }
        val info = org.json.JSONObject()
        menu.info.forEach { (k, v) -> info.put(k, v) }
        info.put("sCodiciValoriPrenotazione", codici)
        info.put("sTipoOperazione", "Modifica")
        info.put("bVisualizzaDatiProssimaPrenotazione", true)
        info.put("lIdPuntoRitiro", -1)
        info.put("bSoloControllo", soloControllo)
        val body = org.json.JSONObject()
            .put("sNomeUtente", menu.nomeUtente)
            .put("sInformazioni", info.toString())
        val r = request(
            "portale/prenotazioni/ITChefWS.asmx/GestionePrenotazioneMenuGiornoWS",
            "POST", body.toString(), "application/json; charset=utf-8"
        )
        val json = try {
            org.json.JSONObject(org.json.JSONObject(r.body).getString("d"))
        } catch (e: Exception) {
            val msg = try { org.json.JSONObject(r.body).optString("Message") } catch (_: Exception) { "" }
            throw ItChefException("Risposta non valida dal portale (${r.code}) ${msg.ifBlank { r.body.take(150) }}")
        }
        val msg = json.optString("sGestionePrenotazioneMenuGiornoWSMessaggio", "")
        return if (soloControllo) msg.ifBlank { null }
        else if (json.optBoolean("bGestionePrenotazioneMenuGiornoWS", false)) null
        else msg.ifBlank { "il portale non ha confermato" }
    }

    /** Controlla e poi salva la prenotazione. Ritorna null se ok, altrimenti il motivo. */
    fun prenota(menu: Menu, scelti: Collection<Piatto>): String? {
        val codici = scelti.map { it.code }.toSet()
        chiamaWS(menu, codici, true)?.let { return it }
        return chiamaWS(menu, codici, false)
    }
}
