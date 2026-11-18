package it.segnaore

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class Entry(val date: LocalDate, val hours: Double, val note: String?)

private class HoursDb(context: Context) :
    SQLiteOpenHelper(context, "segnaore.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "date TEXT PRIMARY KEY, " +
                "hours REAL NOT NULL, " +
                "note TEXT, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/** Archivio locale: una riga per giorno. */
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
    fun save(date: LocalDate, hours: Double, note: String? = null) {
        val cv = ContentValues().apply {
            put("date", date.toString())
            put("hours", hours)
            put("note", note)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        reload()
    }

    @Synchronized
    fun delete(date: LocalDate) {
        db.writableDatabase.delete("entries", "date = ?", arrayOf(date.toString()))
        reload()
    }

    fun get(date: LocalDate): Entry? = _entries.value.firstOrNull { it.date == date }

    private fun reload() {
        val list = mutableListOf<Entry>()
        db.readableDatabase.rawQuery(
            "SELECT date, hours, note FROM entries ORDER BY date DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                list += Entry(
                    LocalDate.parse(c.getString(0)),
                    c.getDouble(1),
                    if (c.isNull(2)) null else c.getString(2)
                )
            }
        }
        _entries.value = list
    }

    fun toCsv(): String = buildString {
        appendLine("data;ore;nota")
        _entries.value.sortedBy { it.date }.forEach { e ->
            val note = (e.note ?: "").replace(";", ",").replace("\n", " ")
            appendLine("${e.date};${HoursParser.formatNumber(e.hours)};$note")
        }
    }
}
