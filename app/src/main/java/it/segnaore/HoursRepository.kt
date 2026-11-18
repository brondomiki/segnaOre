package it.segnaore

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Entry(val date: LocalDate, val hours: Double, val travel: Double, val note: String?)

/** Riga completa, usata per la sincronizzazione. */
data class SyncRow(
    val date: LocalDate,
    val hours: Double,
    val travel: Double,
    val note: String?,
    val deleted: Boolean,
    val updatedMs: Long
)

private class HoursDb(context: Context) :
    SQLiteOpenHelper(context, "segnaore.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "date TEXT PRIMARY KEY, " +
                "hours REAL NOT NULL, " +
                "travel REAL NOT NULL DEFAULT 0, " +
                "note TEXT, " +
                "updated_at INTEGER NOT NULL, " +
                "dirty INTEGER NOT NULL DEFAULT 1, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN travel REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE entries ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** Archivio locale (una riga per giorno), sincronizzato con il cloud quando c'è un account. */
object HoursRepository {
    private lateinit var db: HoursDb
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = HoursDb(context.applicationContext)
        reload()
    }

    @Synchronized
    fun save(date: LocalDate, hours: Double, travel: Double, note: String?) {
        val cv = ContentValues().apply {
            put("date", date.toString())
            put("hours", hours)
            put("travel", travel)
            put("note", note)
            put("updated_at", System.currentTimeMillis())
            put("dirty", 1)
            put("deleted", 0)
        }
        db.writableDatabase.insertWithOnConflict("entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        reload()
    }

    @Synchronized
    fun delete(date: LocalDate) {
        val cv = ContentValues().apply {
            put("deleted", 1)
            put("dirty", 1)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.update("entries", cv, "date = ?", arrayOf(date.toString()))
        reload()
    }

    fun get(date: LocalDate): Entry? = _entries.value.firstOrNull { it.date == date }

    fun between(from: LocalDate, to: LocalDate): List<Entry> =
        _entries.value.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    // ---------- sincronizzazione ----------

    @Synchronized
    fun dirtyRows(): List<SyncRow> = query("dirty = 1")

    @Synchronized
    fun markClean(rows: List<SyncRow>) {
        val w = db.writableDatabase
        rows.forEach { r ->
            w.execSQL(
                "UPDATE entries SET dirty = 0 WHERE date = ? AND updated_at = ?",
                arrayOf<Any>(r.date.toString(), r.updatedMs)
            )
        }
    }

    /** Applica le righe arrivate dal cloud se più recenti di quelle locali. */
    @Synchronized
    fun mergeRemote(remote: List<SyncRow>) {
        val local = query(null).associateBy { it.date }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            remote.forEach { r ->
                val l = local[r.date]
                if (l == null || r.updatedMs > l.updatedMs) {
                    val cv = ContentValues().apply {
                        put("date", r.date.toString())
                        put("hours", r.hours)
                        put("travel", r.travel)
                        put("note", r.note)
                        put("updated_at", r.updatedMs)
                        put("dirty", 0)
                        put("deleted", if (r.deleted) 1 else 0)
                    }
                    w.insertWithOnConflict("entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        reload()
    }

    private fun query(where: String?): List<SyncRow> {
        val list = mutableListOf<SyncRow>()
        db.readableDatabase.rawQuery(
            "SELECT date, hours, travel, note, deleted, updated_at FROM entries" +
                (if (where != null) " WHERE $where" else ""),
            null
        ).use { c ->
            while (c.moveToNext()) {
                list += SyncRow(
                    LocalDate.parse(c.getString(0)),
                    c.getDouble(1),
                    c.getDouble(2),
                    if (c.isNull(3)) null else c.getString(3),
                    c.getInt(4) == 1,
                    c.getLong(5)
                )
            }
        }
        return list
    }

    private fun reload() {
        _entries.value = query("deleted = 0")
            .sortedByDescending { it.date }
            .map { Entry(it.date, it.hours, it.travel, it.note) }
    }

    // ---------- export ----------

    private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)

    fun toCsv(list: List<Entry>): String = buildString {
        appendLine("data;giorno;ore lavorate;ordinarie;straordinarie;festive;viaggio;nota")
        list.sortedBy { it.date }.forEach { e ->
            val s = WorkCalc.split(e.date, e.hours)
            val note = (e.note ?: "").replace(";", ",").replace("\n", " ")
            appendLine(
                listOf(
                    e.date.toString(), e.date.format(dayFmt),
                    HoursParser.formatNumber(e.hours), HoursParser.formatNumber(s.ordinary),
                    HoursParser.formatNumber(s.overtime), HoursParser.formatNumber(s.holiday),
                    HoursParser.formatNumber(e.travel), note
                ).joinToString(";")
            )
        }
        val t = WorkCalc.summarize(list)
        appendLine(
            listOf(
                "TOTALE", "", HoursParser.formatNumber(t.worked), HoursParser.formatNumber(t.ordinary),
                HoursParser.formatNumber(t.overtime), HoursParser.formatNumber(t.holiday),
                HoursParser.formatNumber(t.travel), ""
            ).joinToString(";")
        )
    }
}
