package com.codevnexus.pistola.core

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Una pasada de un chip por el punto: desde que se ve hasta que deja de verse durante la "ventana". */
data class Pasada(
    val id: String,
    val evento: String,
    val epc: String,
    var chip: String,
    var dorsal: String?,
    var nombre: String?,
    var categoria: String?,
    val punto: String,
    val modo: String,
    val dispositivo: String,
    val primera: Long,
    var ultima: Long,
    var mejorTs: Long,
    var rssi: Int?,
    var lecturas: Int,
    var hora: Long,
    var cerrada: Boolean,
    var enviado: Boolean,
    val origen: String,          // vivo | manual | barras
    val sincronizado: Boolean,
) {
    /** Lo que se muestra en grande: el dorsal si se conoce, si no el número de chip. */
    val numero: String get() = dorsal ?: chip
}

data class Inscrito(
    val dorsal: String,
    val chip: String,
    val epc: String,
    val nombre: String,
    val categoria: String,
    val extra: String,
)

data class Evento(
    val id: String,
    val nombre: String,
    val fecha: String,
    val local: Boolean,
    val lugar: String = "",
    val notas: String = "",
)

/** Evento con sus números, para el historial. */
data class EventoResumen(
    val evento: Evento,
    val pasadas: Int,
    val chips: Int,
    val inscritos: Int,
    val pendientes: Int,
    val ultima: Long?,
    val lecturas: Int = 0,
)

/** Inscrito que ya pasó por el punto, con la hora de su primera pasada. */
data class Detectado(val inscrito: Inscrito, val hora: Long, val origen: String)

data class Control(
    val total: Int = 0,
    val pasaron: Int = 0,
    val faltan: List<Inscrito> = emptyList(),
    val detectados: List<Detectado> = emptyList(),
    val desconocidos: Int = 0,
    val desconocidosLista: List<Pasada> = emptyList(),
) {
    val porcentaje: Int get() = if (total == 0) 0 else pasaron * 100 / total
}

data class RegistroBitacora(val ts: Long, val tipo: String, val mensaje: String)

enum class TipoAviso { CONOCIDO, DESCONOCIDO, MANUAL }

data class Aviso(val tipo: TipoAviso, val ts: Long, val titulo: String, val detalle: String)

enum class Estado { DESCONECTADO, CONECTANDO, CONECTADO, RECONECTANDO }

data class Conexion(
    val estado: Estado = Estado.DESCONECTADO,
    val nombre: String = "",
    val mensaje: String = "Sin pistola conectada",
    val comandos: Boolean = true,
)

data class Estadisticas(val chips: Int = 0, val pasadas: Int = 0, val pendientes: Int = 0)

data class Servidor(
    val mensaje: String = "Sin servidor configurado",
    val ok: Boolean = false,
    val desfaseMs: Long? = null,
    val ultimoEnvio: Long? = null,
)

data class Actualizacion(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val notas: String,
    val obligatoria: Boolean,
)

object Modo {
    const val SALIDA = "SALIDA"
    const val CONTROL = "CONTROL"
}

object Regla {
    const val AUTO = "AUTO"        // salida → última lectura, control → primera lectura
    const val PRIMERA = "PRIMERA"
    const val ULTIMA = "ULTIMA"
    const val MEJOR = "MEJOR"      // la de mejor señal
}

object ChipModo {
    const val ULTIMOS = "ULTIMOS"  // últimos N caracteres del EPC, sin ceros a la izquierda
    const val HEX_DEC = "HEX_DEC"  // últimos N caracteres hex convertidos a decimal
    const val COMPLETO = "COMPLETO"
    const val TABLA = "TABLA"      // tabla EPC → chip importada
    const val SEGMENTO = "SEGMENTO" // un tramo del EPC (posición + largo), en hex o decimal
}

data class Ajustes(
    val modo: String = Modo.CONTROL,
    val punto: String = "PC1",
    val dispositivo: String = Build.MODEL ?: "Android",
    val dispositivoId: String = "",
    val operador: String = "",
    val driverId: String = "tsl",
    val ultimaPistola: String = "",
    val chipModo: String = ChipModo.ULTIMOS,
    val chipDigitos: Int = 5,
    val ventanaSeg: Int = 5,
    val regla: String = Regla.AUTO,
    val servidor: String = "",
    val token: String = "",
    val empresa: String = "",
    val autoEnvio: Boolean = true,
    val potencia: Int? = null,
    val sonido: Boolean = true,
    val vibrar: Boolean = true,
    val eventoId: String = "",
    val eventoNombre: String = "",
    val eventoLocal: Boolean = true,
    val comandosConectar: String = "",
    val configurarPistola: Boolean = true,
    val gatilloContinuo: Boolean = true,
    val pitidoPistola: Boolean = false,
    val segInicio: Int = 5,        // posición (1 = primer carácter del EPC)
    val segLargo: Int = 4,
    val segHex: Boolean = true,
) {
    val nombrePunto: String get() = if (modo == Modo.SALIDA) "SALIDA" else punto.ifBlank { "PC" }
    val vinculado: Boolean get() = servidor.isNotBlank() && token.isNotBlank()

    companion object {
        private const val F = "pistola"

        fun cargar(ctx: Context): Ajustes {
            val p = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
            val d = Ajustes()
            val pot = p.getInt("potencia", -1)
            var a = Ajustes(
                modo = p.getString("modo", d.modo) ?: d.modo,
                punto = p.getString("punto", d.punto) ?: d.punto,
                dispositivo = p.getString("dispositivo", d.dispositivo) ?: d.dispositivo,
                dispositivoId = p.getString("dispositivoId", "") ?: "",
                operador = p.getString("operador", d.operador) ?: d.operador,
                driverId = p.getString("driverId", d.driverId) ?: d.driverId,
                ultimaPistola = p.getString("ultimaPistola", "") ?: "",
                chipModo = p.getString("chipModo", d.chipModo) ?: d.chipModo,
                chipDigitos = p.getInt("chipDigitos", d.chipDigitos),
                ventanaSeg = p.getInt("ventanaSeg", d.ventanaSeg),
                regla = p.getString("regla", d.regla) ?: d.regla,
                servidor = p.getString("servidor", "") ?: "",
                token = p.getString("token", "") ?: "",
                empresa = p.getString("empresa", "") ?: "",
                autoEnvio = p.getBoolean("autoEnvio", d.autoEnvio),
                potencia = if (pot < 0) null else pot,
                sonido = p.getBoolean("sonido", d.sonido),
                vibrar = p.getBoolean("vibrar", d.vibrar),
                eventoId = p.getString("eventoId", "") ?: "",
                eventoNombre = p.getString("eventoNombre", "") ?: "",
                eventoLocal = p.getBoolean("eventoLocal", true),
                comandosConectar = p.getString("comandosConectar", "") ?: "",
                configurarPistola = p.getBoolean("configurarPistola", true),
                gatilloContinuo = p.getBoolean("gatilloContinuo", true),
                pitidoPistola = p.getBoolean("pitidoPistola", false),
                segInicio = p.getInt("segInicio", d.segInicio),
                segLargo = p.getInt("segLargo", d.segLargo),
                segHex = p.getBoolean("segHex", d.segHex),
            )
            if (a.dispositivoId.isBlank()) {
                a = a.copy(dispositivoId = UUID.randomUUID().toString())
                guardar(ctx, a)
            }
            return a
        }

        fun guardar(ctx: Context, a: Ajustes) {
            ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
                .putString("modo", a.modo)
                .putString("punto", a.punto)
                .putString("dispositivo", a.dispositivo)
                .putString("dispositivoId", a.dispositivoId)
                .putString("operador", a.operador)
                .putString("driverId", a.driverId)
                .putString("ultimaPistola", a.ultimaPistola)
                .putString("chipModo", a.chipModo)
                .putInt("chipDigitos", a.chipDigitos)
                .putInt("ventanaSeg", a.ventanaSeg)
                .putString("regla", a.regla)
                .putString("servidor", a.servidor)
                .putString("token", a.token)
                .putString("empresa", a.empresa)
                .putBoolean("autoEnvio", a.autoEnvio)
                .putInt("potencia", a.potencia ?: -1)
                .putBoolean("sonido", a.sonido)
                .putBoolean("vibrar", a.vibrar)
                .putString("eventoId", a.eventoId)
                .putString("eventoNombre", a.eventoNombre)
                .putBoolean("eventoLocal", a.eventoLocal)
                .putString("comandosConectar", a.comandosConectar)
                .putBoolean("configurarPistola", a.configurarPistola)
                .putBoolean("gatilloContinuo", a.gatilloContinuo)
                .putBoolean("pitidoPistola", a.pitidoPistola)
                .putInt("segInicio", a.segInicio)
                .putInt("segLargo", a.segLargo)
                .putBoolean("segHex", a.segHex)
                .apply()
        }

        fun nombreEventoLocal(): String =
            "Evento local " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }
}

object ChipDecoder {
    fun decodificar(epc: String, a: Ajustes, tabla: Map<String, String>): String {
        val e = epc.trim().uppercase()
        return when (a.chipModo) {
            ChipModo.COMPLETO -> e
            ChipModo.HEX_DEC -> e.takeLast(a.chipDigitos.coerceIn(1, 15)).toLongOrNull(16)?.toString() ?: e
            ChipModo.TABLA -> tabla[e] ?: ("?" + ultimos(e, a.chipDigitos))
            ChipModo.SEGMENTO -> segmento(e, a.segInicio, a.segLargo, a.segHex) ?: e
            else -> ultimos(e, a.chipDigitos)
        }
    }

    fun segmento(e: String, inicio: Int, largo: Int, hex: Boolean): String? {
        val i = inicio - 1
        if (i < 0 || largo < 1 || i + largo > e.length) return null
        val t = e.substring(i, i + largo)
        return if (hex) {
            if (largo > 15) null else t.toLongOrNull(16)?.toString()
        } else {
            if (!t.all(Char::isDigit)) null else t.trimStart('0').ifEmpty { "0" }
        }
    }

    /**
     * Busca dónde está el número impreso dentro del EPC.
     * @return (posición 1-based, largo, esHex) o null si no aparece.
     */
    fun detectar(epc: String, numero: String): Triple<Int, Int, Boolean>? {
        val e = epc.trim().uppercase()
        val n = numero.trim().toLongOrNull() ?: return null
        val candidatos = ArrayList<Pair<String, Boolean>>()
        for (largo in listOf(4, 6, 8, 2, 3, 5, 7)) {
            val h = n.toString(16).uppercase()
            if (h.length <= largo) candidatos += h.padStart(largo, '0') to true
        }
        for (largo in listOf(4, 5, 6, 8, 3, 7)) {
            val d = n.toString()
            if (d.length <= largo) candidatos += d.padStart(largo, '0') to false
        }
        for ((patron, hex) in candidatos) {
            val i = e.indexOf(patron)
            if (i >= 0 && segmento(e, i + 1, patron.length, hex) == n.toString()) return Triple(i + 1, patron.length, hex)
        }
        return null
    }

    private fun ultimos(e: String, n: Int): String {
        val t = e.takeLast(n.coerceIn(1, 24)).trimStart('0')
        return t.ifEmpty { "0" }
    }
}

/** Datos de un QR / enlace de vinculación: nexcrono://vincular?u=URL&c=CODIGO&p=PUNTO&m=MODO&e=EVENTO&en=NOMBRE */
data class Vinculo(
    val url: String,
    val codigo: String,
    val punto: String?,
    val modo: String?,
    val eventoId: String?,
    val eventoNombre: String?,
) {
    companion object {
        fun desde(texto: String): Vinculo? {
            val t = texto.trim()
            if (!t.startsWith("nexcrono://vincular", ignoreCase = true)) return null
            val q = t.substringAfter('?', "")
            val m = HashMap<String, String>()
            for (par in q.split('&')) {
                val k = par.substringBefore('=', "")
                val v = par.substringAfter('=', "")
                if (k.isNotEmpty()) m[k] = java.net.URLDecoder.decode(v, "UTF-8")
            }
            val url = m["u"]?.trim()?.trimEnd('/') ?: return null
            val codigo = m["c"]?.trim() ?: return null
            if (!(url.startsWith("http://") || url.startsWith("https://")) || codigo.isEmpty()) return null
            return Vinculo(
                url, codigo, m["p"]?.takeIf { it.isNotBlank() },
                m["m"]?.uppercase()?.takeIf { it == Modo.SALIDA || it == Modo.CONTROL },
                m["e"]?.takeIf { it.isNotBlank() }, m["en"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Estado de la herramienta «Grabar número en chips». */
data class Grabacion(
    val ocupado: Boolean = false,
    val epcActual: String? = null,
    val rssi: Int? = null,
    val mensaje: String = "",
    val ok: Boolean? = null,
    val verificado: String? = null,
)

/** Cada vez que la pistola leyó un chip (antes de agruparlo en pasadas). */
data class LecturaCruda(
    val evento: String,
    val pasada: String,
    val epc: String,
    val ts: Long,
    val rssi: Int?,
    val origen: String,      // vivo | memoria | manual | barras
    val punto: String,
    val dispositivo: String,
)

/** Lectura cruda con los datos del atleta, para exportar. */
data class LecturaExportable(val l: LecturaCruda, val dorsal: String?, val nombre: String?, val modo: String?)
