package com.codevnexus.pistola.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Todo queda guardado en el teléfono: si la app se cierra o no hay red, no se pierde nada. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "pistola.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pasadas(
                id TEXT PRIMARY KEY, epc TEXT NOT NULL, chip TEXT NOT NULL,
                punto TEXT, modo TEXT, dispositivo TEXT,
                primera INTEGER, ultima INTEGER, mejor_ts INTEGER, rssi INTEGER,
                lecturas INTEGER, hora INTEGER,
                cerrada INTEGER NOT NULL DEFAULT 0, enviado INTEGER NOT NULL DEFAULT 0,
                origen TEXT, sincronizado INTEGER,
                evento TEXT NOT NULL DEFAULT '', dorsal TEXT, nombre TEXT, categoria TEXT)"""
        )
        db.execSQL("CREATE INDEX ix_envio ON pasadas(cerrada, enviado)")
        db.execSQL("CREATE INDEX ix_primera ON pasadas(primera)")
        db.execSQL("CREATE TABLE mapa(epc TEXT PRIMARY KEY, chip TEXT NOT NULL)")
        crearV2(db)
        crearV3(db, nueva = true)
    }

    /** v3: datos del evento, estado de sincronización de cada pasada, bitácora y estados guardados. */
    private fun crearV3(db: SQLiteDatabase, nueva: Boolean) {
        if (nueva) {
            db.execSQL("ALTER TABLE eventos ADD COLUMN lugar TEXT")
            db.execSQL("ALTER TABLE eventos ADD COLUMN notas TEXT")
            db.execSQL("ALTER TABLE eventos ADD COLUMN creado INTEGER")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN enviado_en INTEGER")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN intentos INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN error TEXT")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bitacora(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, tipo TEXT, mensaje TEXT)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_estado(clave TEXT PRIMARY KEY, valor TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_dorsal ON pasadas(evento, dorsal)")
    }

    private fun crearV2(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_evento ON pasadas(evento, primera)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS inscritos(
                evento TEXT NOT NULL, dorsal TEXT NOT NULL, chip TEXT, epc TEXT,
                nombre TEXT, categoria TEXT, extra TEXT, PRIMARY KEY(evento, dorsal))"""
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS eventos(id TEXT PRIMARY KEY, nombre TEXT, fecha TEXT, local INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pasadas ADD COLUMN evento TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN dorsal TEXT")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN nombre TEXT")
            db.execSQL("ALTER TABLE pasadas ADD COLUMN categoria TEXT")
            crearV2(db)
            // lo leído con la versión 1.0 queda en su propio evento
            db.execSQL("UPDATE pasadas SET evento = 'anterior'")
            db.execSQL("INSERT OR IGNORE INTO eventos(id, nombre, fecha, local) VALUES('anterior', 'Lecturas versión 1.0', '', 1)")
        }
        if (oldVersion < 3) crearV3(db, nueva = true)
    }

    // ───────────── Pasadas ─────────────

    fun guardar(p: Pasada) {
        val cv = ContentValues().apply {
            put("id", p.id); put("evento", p.evento); put("epc", p.epc); put("chip", p.chip)
            put("dorsal", p.dorsal); put("nombre", p.nombre); put("categoria", p.categoria)
            put("punto", p.punto); put("modo", p.modo); put("dispositivo", p.dispositivo)
            put("primera", p.primera); put("ultima", p.ultima); put("mejor_ts", p.mejorTs)
            if (p.rssi != null) put("rssi", p.rssi) else putNull("rssi")
            put("lecturas", p.lecturas); put("hora", p.hora)
            put("cerrada", if (p.cerrada) 1 else 0); put("enviado", if (p.enviado) 1 else 0)
            put("origen", p.origen); put("sincronizado", if (p.sincronizado) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("pasadas", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun existe(id: String): Boolean =
        android.database.DatabaseUtils.queryNumEntries(readableDatabase, "pasadas", "id = ?", arrayOf(id)) > 0

    fun recientes(evento: String, limite: Int): List<Pasada> =
        consulta("SELECT * FROM pasadas WHERE evento = ? ORDER BY primera DESC LIMIT $limite", evento)

    fun todas(evento: String): List<Pasada> =
        consulta("SELECT * FROM pasadas WHERE evento = ? ORDER BY hora ASC", evento)

    fun abiertas(): List<Pasada> = consulta("SELECT * FROM pasadas WHERE cerrada = 0")

    fun pendientes(limite: Int): List<Pasada> =
        consulta("SELECT * FROM pasadas WHERE cerrada = 1 AND enviado = 0 ORDER BY hora ASC LIMIT $limite")

    fun marcarEnviados(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ahora = System.currentTimeMillis()
            for (id in ids) db.execSQL(
                "UPDATE pasadas SET enviado = 1, enviado_en = ?, error = NULL WHERE id = ?", arrayOf<Any>(ahora, id)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun reenviarEvento(evento: String) {
        writableDatabase.execSQL("UPDATE pasadas SET enviado = 0 WHERE evento = ?", arrayOf(evento))
    }

    fun estadisticas(evento: String): Estadisticas {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT chip), " +
                "SUM(CASE WHEN cerrada = 1 AND enviado = 0 THEN 1 ELSE 0 END) FROM pasadas WHERE evento = ?",
            arrayOf(evento)
        ).use { c ->
            return if (c.moveToFirst()) Estadisticas(c.getInt(1), c.getInt(0), c.getInt(2)) else Estadisticas()
        }
    }

    fun pendientesTotales(): Int =
        android.database.DatabaseUtils.queryNumEntries(readableDatabase, "pasadas", "cerrada = 1 AND enviado = 0").toInt()

    /** Recalcula chip y datos del inscrito de cada pasada del evento (tras importar inscritos o cambiar la conversión). */
    fun reasignar(evento: String, chipDe: (String) -> String, inscritoDe: (epc: String, chip: String) -> Inscrito?) {
        val db = writableDatabase
        val filas = ArrayList<Pair<String, String>>()   // id, epc
        db.rawQuery("SELECT id, epc FROM pasadas WHERE evento = ? AND origen = 'vivo'", arrayOf(evento)).use { c ->
            while (c.moveToNext()) filas.add(c.getString(0) to c.getString(1))
        }
        db.beginTransaction()
        try {
            for ((id, epc) in filas) {
                val chip = chipDe(epc)
                val i = inscritoDe(epc, chip)
                db.execSQL(
                    "UPDATE pasadas SET chip = ?, dorsal = ?, nombre = ?, categoria = ? WHERE id = ?",
                    arrayOf<Any?>(chip, i?.dorsal, i?.nombre, i?.categoria, id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun borrarEvento(evento: String) {
        writableDatabase.execSQL("DELETE FROM pasadas WHERE evento = ?", arrayOf(evento))
    }

    // ───────────── Inscritos y eventos ─────────────

    fun guardarInscritos(evento: String, lista: List<Inscrito>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM inscritos WHERE evento = ?", arrayOf(evento))
            for (i in lista) {
                val cv = ContentValues().apply {
                    put("evento", evento); put("dorsal", i.dorsal); put("chip", i.chip); put("epc", i.epc)
                    put("nombre", i.nombre); put("categoria", i.categoria); put("extra", i.extra)
                }
                db.insertWithOnConflict("inscritos", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun inscritos(evento: String): List<Inscrito> {
        val l = ArrayList<Inscrito>()
        readableDatabase.rawQuery(
            "SELECT dorsal, chip, epc, nombre, categoria, extra FROM inscritos WHERE evento = ?", arrayOf(evento)
        ).use { c ->
            while (c.moveToNext()) {
                l.add(
                    Inscrito(
                        c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: ""
                    )
                )
            }
        }
        return l
    }

    fun guardarEvento(e: Evento) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("nombre", e.nombre); put("fecha", e.fecha); put("local", if (e.local) 1 else 0)
            put("lugar", e.lugar); put("notas", e.notas)
        }
        // actualizar sin perder la fecha de creación; si no existe, insertar
        if (db.update("eventos", cv, "id = ?", arrayOf(e.id)) == 0) {
            cv.put("id", e.id)
            cv.put("creado", System.currentTimeMillis())
            db.insert("eventos", null, cv)
        }
    }

    fun evento(id: String): Evento? = eventosDonde("WHERE e.id = ?", arrayOf(id)).firstOrNull()?.evento

    /** Historial: todos los eventos con sus números, del más reciente al más antiguo. */
    fun eventosResumen(): List<EventoResumen> = eventosDonde("", null)

    private fun eventosDonde(filtro: String, args: Array<String>?): List<EventoResumen> {
        val l = ArrayList<EventoResumen>()
        readableDatabase.rawQuery(
            "SELECT e.id, e.nombre, e.fecha, e.local, e.lugar, e.notas, " +
                "(SELECT COUNT(*) FROM pasadas p WHERE p.evento = e.id), " +
                "(SELECT COUNT(DISTINCT chip) FROM pasadas p WHERE p.evento = e.id), " +
                "(SELECT COUNT(*) FROM inscritos i WHERE i.evento = e.id), " +
                "(SELECT COUNT(*) FROM pasadas p WHERE p.evento = e.id AND p.cerrada = 1 AND p.enviado = 0), " +
                "(SELECT MAX(primera) FROM pasadas p WHERE p.evento = e.id), e.creado " +
                "FROM eventos e $filtro ORDER BY COALESCE((SELECT MAX(primera) FROM pasadas p WHERE p.evento = e.id), e.creado, 0) DESC",
            args
        ).use { c ->
            while (c.moveToNext()) {
                l.add(
                    EventoResumen(
                        Evento(
                            c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) == 1,
                            c.getString(4) ?: "", c.getString(5) ?: "",
                        ),
                        pasadas = c.getInt(6), chips = c.getInt(7), inscritos = c.getInt(8), pendientes = c.getInt(9),
                        ultima = if (c.isNull(10)) null else c.getLong(10),
                    )
                )
            }
        }
        return l
    }

    /** Borra el evento con sus pasadas e inscritos. */
    fun borrarEventoCompleto(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM pasadas WHERE evento = ?", arrayOf(id))
            db.execSQL("DELETE FROM inscritos WHERE evento = ?", arrayOf(id))
            db.execSQL("DELETE FROM eventos WHERE id = ?", arrayOf(id))
            db.execSQL("DELETE FROM sync_estado WHERE clave LIKE ?", arrayOf("%:$id"))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ───────────── Detectados / no inscritos ─────────────

    /** dorsal → (primera hora registrada, origen de esa pasada) */
    fun primeraPorDorsal(evento: String): Map<String, Pair<Long, String>> {
        val m = HashMap<String, Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT dorsal, hora, origen FROM pasadas WHERE evento = ? AND dorsal IS NOT NULL ORDER BY hora ASC",
            arrayOf(evento)
        ).use { c ->
            while (c.moveToNext()) {
                val d = c.getString(0)
                if (d !in m) m[d] = c.getLong(1) to (c.getString(2) ?: "")
            }
        }
        return m
    }

    /** Una pasada por cada chip leído que no está en la lista de inscritos. */
    fun noInscritos(evento: String): List<Pasada> =
        consulta(
            // en SQLite, con MIN() la columna id sale de la fila con la hora mínima
            "SELECT * FROM pasadas WHERE id IN (SELECT id FROM (SELECT id, MIN(hora) FROM pasadas " +
                "WHERE evento = ? AND dorsal IS NULL AND epc <> '' GROUP BY epc)) ORDER BY hora ASC", evento
        )

    // ───────────── Estados de sincronización y bitácora ─────────────

    fun marcarError(ids: List<String>, error: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.execSQL(
                "UPDATE pasadas SET intentos = intentos + 1, error = ? WHERE id = ?", arrayOf(error.take(200), id)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ponerEstado(clave: String, valor: String) {
        val cv = ContentValues().apply { put("clave", clave); put("valor", valor) }
        writableDatabase.insertWithOnConflict("sync_estado", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun estado(clave: String): String? {
        readableDatabase.rawQuery("SELECT valor FROM sync_estado WHERE clave = ?", arrayOf(clave)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun anotar(tipo: String, mensaje: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("tipo", tipo); put("mensaje", mensaje.take(500))
        }
        db.insert("bitacora", null, cv)
        // conservar solo los últimos 1000 registros
        db.execSQL("DELETE FROM bitacora WHERE id <= (SELECT MAX(id) FROM bitacora) - 1000")
    }

    fun bitacora(limite: Int): List<RegistroBitacora> {
        val l = ArrayList<RegistroBitacora>()
        readableDatabase.rawQuery("SELECT ts, tipo, mensaje FROM bitacora ORDER BY id DESC LIMIT $limite", null).use { c ->
            while (c.moveToNext()) l.add(RegistroBitacora(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        return l
    }

    fun eventos(): List<Evento> = eventosResumen().map { it.evento }

    // ───────────── Tabla EPC → chip ─────────────

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

    private fun consulta(sql: String, vararg args: String): List<Pasada> {
        val out = ArrayList<Pasada>()
        readableDatabase.rawQuery(sql, if (args.isEmpty()) null else arrayOf(*args)).use { c ->
            while (c.moveToNext()) out.add(leer(c))
        }
        return out
    }

    private fun leer(c: Cursor): Pasada {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
        fun sn(n: String): String? = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
        val ri = c.getColumnIndexOrThrow("rssi")
        return Pasada(
            id = s("id"), evento = s("evento"), epc = s("epc"), chip = s("chip"),
            dorsal = sn("dorsal"), nombre = sn("nombre"), categoria = sn("categoria"),
            punto = s("punto"), modo = s("modo"),
            dispositivo = s("dispositivo"), primera = l("primera"), ultima = l("ultima"),
            mejorTs = l("mejor_ts"), rssi = if (c.isNull(ri)) null else c.getInt(ri),
            lecturas = i("lecturas"), hora = l("hora"), cerrada = i("cerrada") == 1,
            enviado = i("enviado") == 1, origen = s("origen"), sincronizado = i("sincronizado") == 1,
        )
    }
}
