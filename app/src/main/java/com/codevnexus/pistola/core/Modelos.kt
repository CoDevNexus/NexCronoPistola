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

data class Evento(val id: String, val nombre: String, val fecha: String, val local: Boolean)

data class Control(
    val total: Int = 0,
    val pasaron: Int = 0,
    val faltan: List<Inscrito> = emptyList(),
    val desconocidos: Int = 0,
)

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
            else -> ultimos(e, a.chipDigitos)
        }
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
