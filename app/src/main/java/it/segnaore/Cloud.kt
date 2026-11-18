package it.segnaore

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Collegamento a Supabase: account, sincronizzazione delle ore e report PDF.
 * Tutte le funzioni sono bloccanti: chiamarle da un thread in background.
 */
object Cloud {
    const val URL_BASE = "https://ehwysdqadddelbdielfu.supabase.co"
    const val API_KEY = "sb_publishable_kHGaX6xC6znNfWGrFxmtnw_6guDWLrT"

    class CloudException(message: String) : Exception(message)

    data class Status(val loggedIn: Boolean, val email: String?, val lastSync: Long, val error: String?)

    private val _status = MutableStateFlow(Status(false, null, 0, null))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun refreshStatus(ctx: Context, error: String? = _status.value.error) {
        val p = sp(ctx)
        _status.value = Status(
            p.getString("refresh", null) != null,
            p.getString("email", null),
            p.getLong("last_sync", 0),
            error
        )
    }

    fun isLoggedIn(ctx: Context) = sp(ctx).getString("refresh", null) != null

    // ---------- HTTP ----------

    private class Resp(val code: Int, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    private fun http(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Resp {
        val conn = URL(URL_BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", API_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${token ?: API_KEY}")
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, bytes)
        } catch (e: java.io.IOException) {
            throw CloudException("Connessione non disponibile")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(r: Resp): String {
        val msg = runCatching {
            val o = JSONObject(r.text)
            o.optString("msg").ifEmpty { o.optString("error_description") }
                .ifEmpty { o.optString("message") }.ifEmpty { o.optString("error") }
        }.getOrNull().orEmpty()
        return when {
            msg.contains("Invalid login credentials", true) -> "Email o password errate"
            msg.contains("Email not confirmed", true) -> "Conferma prima l'email (controlla la posta)"
            msg.contains("already registered", true) -> "Email già registrata: usa Accedi"
            msg.contains("Password should be", true) -> "La password deve avere almeno 6 caratteri"
            msg.isNotEmpty() -> msg
            else -> "Errore ${r.code}"
        }
    }

    // ---------- account ----------

    private fun saveSession(ctx: Context, o: JSONObject) {
        val user = o.getJSONObject("user")
        val expiresAt = o.optLong("expires_at", 0).takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600))
        sp(ctx).edit()
            .putString("access", o.getString("access_token"))
            .putString("refresh", o.getString("refresh_token"))
            .putLong("expires_at", expiresAt)
            .putString("user_id", user.getString("id"))
            .putString("email", user.optString("email"))
            .apply()
    }

    fun signIn(ctx: Context, email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val r = http("POST", "/auth/v1/token?grant_type=password", body)
        if (r.code !in 200..299) throw CloudException(errorMessage(r))
        saveSession(ctx, JSONObject(r.text))
        sp(ctx).edit().putBoolean("settings_ok", false).apply()
        refreshStatus(ctx, null)
    }

    /** @return true se l'account è già attivo, false se serve confermare l'email. */
    fun signUp(ctx: Context, email: String, password: String): Boolean {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val r = http("POST", "/auth/v1/signup", body)
        if (r.code !in 200..299) throw CloudException(errorMessage(r))
        val o = JSONObject(r.text)
        if (o.has("access_token")) {
            saveSession(ctx, o)
            refreshStatus(ctx, null)
            return true
        }
        return false
    }

    fun signOut(ctx: Context) {
        sp(ctx).edit().clear().apply()
        refreshStatus(ctx, null)
    }

    private fun token(ctx: Context): String {
        val p = sp(ctx)
        val refresh = p.getString("refresh", null) ?: throw CloudException("Non hai effettuato l'accesso")
        val access = p.getString("access", null)
        if (access != null && p.getLong("expires_at", 0) - 60 > System.currentTimeMillis() / 1000) {
            return access
        }
        val r = http(
            "POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh).toString()
        )
        if (r.code !in 200..299) {
            if (r.code in 400..499) signOut(ctx)
            throw CloudException("Sessione scaduta: accedi di nuovo")
        }
        saveSession(ctx, JSONObject(r.text))
        return sp(ctx).getString("access", null)!!
    }

    // ---------- sincronizzazione ----------

    /** Scarica le modifiche fatte altrove (es. dal sito) e invia quelle locali. */
    @Synchronized
    fun sync(ctx: Context) {
        val t = token(ctx)
        val uid = sp(ctx).getString("user_id", null)!!

        if (!sp(ctx).getBoolean("settings_ok", false)) {
            val r = http(
                "POST", "/rest/v1/segnaore_settings",
                JSONObject().put("user_id", uid).toString(), t,
                mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
            )
            if (r.code in 200..299) sp(ctx).edit().putBoolean("settings_ok", true).apply()
        }

        // 1) scarica
        val get = http(
            "GET",
            "/rest/v1/segnaore_entries?select=date,hours,travel_hours,note,deleted,updated_ms",
            token = t
        )
        if (get.code !in 200..299) throw CloudException(errorMessage(get))
        val arr = JSONArray(get.text)
        val remote = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SyncRow(
                LocalDate.parse(o.getString("date")),
                o.getDouble("hours"),
                o.getDouble("travel_hours"),
                if (o.isNull("note")) null else o.getString("note"),
                o.getBoolean("deleted"),
                o.getLong("updated_ms")
            )
        }
        HoursRepository.mergeRemote(remote)

        // 2) invia
        val dirty = HoursRepository.dirtyRows()
        if (dirty.isNotEmpty()) {
            val payload = JSONArray()
            dirty.forEach { d ->
                payload.put(
                    JSONObject()
                        .put("user_id", uid)
                        .put("date", d.date.toString())
                        .put("hours", d.hours)
                        .put("travel_hours", d.travel)
                        .put("note", d.note ?: JSONObject.NULL)
                        .put("deleted", d.deleted)
                        .put("updated_ms", d.updatedMs)
                )
            }
            val post = http(
                "POST", "/rest/v1/segnaore_entries?on_conflict=user_id,date",
                payload.toString(), t,
                mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
            if (post.code !in 200..299) throw CloudException(errorMessage(post))
            HoursRepository.markClean(dirty)
        }

        sp(ctx).edit().putLong("last_sync", System.currentTimeMillis()).apply()
        refreshStatus(ctx, null)
    }

    /** Sincronizza in background senza bloccare (se c'è un account). */
    fun syncAsync(ctx: Context, onDone: ((String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!isLoggedIn(app)) {
            onDone?.invoke(null)
            return
        }
        executor.execute {
            val err = try {
                sync(app); null
            } catch (e: Exception) {
                e.message ?: "Errore di sincronizzazione"
            }
            refreshStatus(app, err)
            onDone?.invoke(err)
        }
    }

    // ---------- report ----------

    private fun reportBody(action: String, from: LocalDate?, to: LocalDate?, month: String?): String {
        val o = JSONObject().put("action", action)
        if (month != null) o.put("month", month)
        if (from != null) o.put("from", from.toString())
        if (to != null) o.put("to", to.toString())
        return o.toString()
    }

    /** Invia via email il report PDF. @return indirizzo a cui è stato inviato. */
    fun sendReport(ctx: Context, from: LocalDate? = null, to: LocalDate? = null, month: String? = null): String {
        sync(ctx)
        val r = http("POST", "/functions/v1/segnaore-report", reportBody("send", from, to, month), token(ctx))
        if (r.code !in 200..299) throw CloudException(errorMessage(r))
        return JSONObject(r.text).optString("sentTo")
    }

    /** Scarica il report PDF del periodo. */
    fun downloadReport(ctx: Context, from: LocalDate, to: LocalDate): ByteArray {
        sync(ctx)
        val r = http("POST", "/functions/v1/segnaore-report", reportBody("download", from, to, null), token(ctx))
        if (r.code !in 200..299) throw CloudException(errorMessage(r))
        return r.body
    }
}
