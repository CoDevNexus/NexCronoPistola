package com.codevnexus.pistola.core

import android.content.Context
import android.os.Build

/** Una pasada de un chip por el punto: desde que se ve hasta que deja de verse durante la "ventana". */
data class Pasada(
    val id: String,
    val epc: String,
    var chip: String,
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
    val origen: String,
    val sincronizado: Boolean,
)

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
    val operador: String = "",
    val driverId: String = "tsl",
    val ultimaPistola: String = "",
    val chipModo: String = ChipModo.ULTIMOS,
    val chipDigitos: Int = 5,
    val ventanaSeg: Int = 5,
    val regla: String = Regla.AUTO,
    val servidor: String = "",
    val token: String = "",
    val autoEnvio: Boolean = true,
    val potencia: Int? = null,
    val sonido: Boolean = true,
) {
    val nombrePunto: String get() = if (modo == Modo.SALIDA) "SALIDA" else punto.ifBlank { "PC" }

    companion object {
        private const val F = "pistola"

        fun cargar(ctx: Context): Ajustes {
            val p = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
            val d = Ajustes()
            val pot = p.getInt("potencia", -1)
            return Ajustes(
                modo = p.getString("modo", d.modo) ?: d.modo,
                punto = p.getString("punto", d.punto) ?: d.punto,
                dispositivo = p.getString("dispositivo", d.dispositivo) ?: d.dispositivo,
                operador = p.getString("operador", d.operador) ?: d.operador,
                driverId = p.getString("driverId", d.driverId) ?: d.driverId,
                ultimaPistola = p.getString("ultimaPistola", "") ?: "",
                chipModo = p.getString("chipModo", d.chipModo) ?: d.chipModo,
                chipDigitos = p.getInt("chipDigitos", d.chipDigitos),
                ventanaSeg = p.getInt("ventanaSeg", d.ventanaSeg),
                regla = p.getString("regla", d.regla) ?: d.regla,
                servidor = p.getString("servidor", "") ?: "",
                token = p.getString("token", "") ?: "",
                autoEnvio = p.getBoolean("autoEnvio", d.autoEnvio),
                potencia = if (pot < 0) null else pot,
                sonido = p.getBoolean("sonido", d.sonido),
            )
        }

        fun guardar(ctx: Context, a: Ajustes) {
            ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
                .putString("modo", a.modo)
                .putString("punto", a.punto)
                .putString("dispositivo", a.dispositivo)
                .putString("operador", a.operador)
                .putString("driverId", a.driverId)
                .putString("ultimaPistola", a.ultimaPistola)
                .putString("chipModo", a.chipModo)
                .putInt("chipDigitos", a.chipDigitos)
                .putInt("ventanaSeg", a.ventanaSeg)
                .putString("regla", a.regla)
                .putString("servidor", a.servidor)
                .putString("token", a.token)
                .putBoolean("autoEnvio", a.autoEnvio)
                .putInt("potencia", a.potencia ?: -1)
                .putBoolean("sonido", a.sonido)
                .apply()
        }
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
