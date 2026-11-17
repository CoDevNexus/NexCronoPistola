package com.codevnexus.pistola.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Todo queda guardado en el teléfono: si la app se cierra o no hay red, no se pierde nada. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "pistola.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pasadas(
                id TEXT PRIMARY KEY, epc TEXT NOT NULL, chip TEXT NOT NULL,
                punto TEXT, modo TEXT, dispositivo TEXT,
                primera INTEGER, ultima INTEGER, mejor_ts INTEGER, rssi INTEGER,
                lecturas INTEGER, hora INTEGER,
                cerrada INTEGER NOT NULL DEFAULT 0, enviado INTEGER NOT NULL DEFAULT 0,
                origen TEXT, sincronizado INTEGER)"""
        )
        db.execSQL("CREATE INDEX ix_envio ON pasadas(cerrada, enviado)")
        db.execSQL("CREATE INDEX ix_primera ON pasadas(primera)")
        db.execSQL("CREATE TABLE mapa(epc TEXT PRIMARY KEY, chip TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun guardar(p: Pasada) {
        val cv = ContentValues().apply {
            put("id", p.id); put("epc", p.epc); put("chip", p.chip)
            put("punto", p.punto); put("modo", p.modo); put("dispositivo", p.dispositivo)
            put("primera", p.primera); put("ultima", p.ultima); put("mejor_ts", p.mejorTs)
            if (p.rssi != null) put("rssi", p.rssi) else putNull("rssi")
            put("lecturas", p.lecturas); put("hora", p.hora)
            put("cerrada", if (p.cerrada) 1 else 0); put("enviado", if (p.enviado) 1 else 0)
            put("origen", p.origen); put("sincronizado", if (p.sincronizado) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("pasadas", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recientes(limite: Int): List<Pasada> =
        consulta("SELECT * FROM pasadas ORDER BY primera DESC LIMIT $limite")

    fun todas(): List<Pasada> = consulta("SELECT * FROM pasadas ORDER BY hora ASC")

    fun abiertas(): List<Pasada> = consulta("SELECT * FROM pasadas WHERE cerrada = 0")

    fun pendientes(limite: Int): List<Pasada> =
        consulta("SELECT * FROM pasadas WHERE cerrada = 1 AND enviado = 0 ORDER BY hora ASC LIMIT $limite")

    fun marcarEnviados(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.execSQL("UPDATE pasadas SET enviado = 1 WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun reenviarTodo() {
        writableDatabase.execSQL("UPDATE pasadas SET enviado = 0")
    }

    fun estadisticas(): Estadisticas {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT chip), " +
                "SUM(CASE WHEN cerrada = 1 AND enviado = 0 THEN 1 ELSE 0 END) FROM pasadas", null
        ).use { c ->
            return if (c.moveToFirst()) Estadisticas(c.getInt(1), c.getInt(0), c.getInt(2)) else Estadisticas()
        }
    }

    fun recalcularChips(f: (String) -> String) {
        val db = writableDatabase
        val epcs = mutableListOf<String>()
        db.rawQuery("SELECT DISTINCT epc FROM pasadas", null).use { c -> while (c.moveToNext()) epcs.add(c.getString(0)) }
        db.beginTransaction()
        try {
            for (e in epcs) db.execSQL("UPDATE pasadas SET chip = ? WHERE epc = ?", arrayOf(f(e), e))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun borrarTodo() {
        writableDatabase.execSQL("DELETE FROM pasadas")
    }

    fun cargarMapa(): Map<String, String> {
        val m = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT epc, chip FROM mapa", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        }
        return m
    }

    fun guardarMapa(m: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM mapa")
            for ((k, v) in m) {
                val cv = ContentValues().apply { put("epc", k); put("chip", v) }
                db.insertWithOnConflict("mapa", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun consulta(sql: String): List<Pasada> {
        val out = ArrayList<Pasada>()
        readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) out.add(leer(c)) }
        return out
    }

    private fun leer(c: Cursor): Pasada {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
        val ri = c.getColumnIndexOrThrow("rssi")
        return Pasada(
            id = s("id"), epc = s("epc"), chip = s("chip"), punto = s("punto"), modo = s("modo"),
            dispositivo = s("dispositivo"), primera = l("primera"), ultima = l("ultima"),
            mejorTs = l("mejor_ts"), rssi = if (c.isNull(ri)) null else c.getInt(ri),
            lecturas = i("lecturas"), hora = l("hora"), cerrada = i("cerrada") == 1,
            enviado = i("enviado") == 1, origen = s("origen"), sincronizado = i("sincronizado") == 1,
        )
    }
}
