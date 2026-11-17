package com.codevnexus.pistola.core

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.codevnexus.pistola.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Licencia de uso (archivo .lic) firmada por CodevNexus y vinculada a un teléfono.
 *
 * Formato del archivo (texto):
 *   NXCLIC1
 *   <datos en Base64>      ← líneas clave=valor: app, cliente, telefono, plan, emitida, vence, id
 *   <firma en Base64>      ← ECDSA P-256 / SHA-256 de los bytes de los datos
 *
 * La app solo tiene la clave PÚBLICA (BuildConfig.LICENSE_PUBLIC_KEY): puede verificar, no fabricar.
 * Sin licencia válida funciona en modo demostración (pocas pasadas por evento).
 */
object Licencia {

    const val DEMO_PASADAS = 25
    private const val ARCHIVO = "licencia.lic"
    private const val PREFS = "licencia"

    data class Datos(
        val cliente: String,
        val telefono: String,
        val plan: String,
        val emitida: String,
        val vence: String,      // yyyy-MM-dd o "perpetua"
        val id: String,
    )

    sealed class Estado {
        /** Compilación sin clave pública: no se controla la licencia (desarrollo). */
        object SinControl : Estado()
        object SinLicencia : Estado()
        data class Valida(val datos: Datos, val diasRestantes: Long?) : Estado()
        data class Vencida(val datos: Datos) : Estado()
        data class OtroTelefono(val datos: Datos) : Estado()
        data class Invalida(val motivo: String) : Estado()
        object RelojAlterado : Estado()
    }

    val estado = MutableStateFlow<Estado>(Estado.SinLicencia)

    /** Código de este teléfono, que se envía a CodevNexus para emitir la licencia. */
    @SuppressLint("HardwareIds")
    fun codigoTelefono(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "sin-id"
        return codigoDesde(androidId, ctx.packageName)
    }

    fun codigoDesde(androidId: String, paquete: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("$androidId|$paquete|nexcrono".toByteArray())
        return h.take(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }

    fun verificar(ctx: Context) {
        val f = File(ctx.filesDir, ARCHIVO)
        estado.value = evaluar(ctx, if (f.exists()) f.readText() else null)
    }

    /** Carga un .lic. Solo se guarda si es válido para este teléfono. */
    fun cargar(ctx: Context, texto: String): Estado {
        val e = evaluar(ctx, texto)
        if (e is Estado.Valida) {
            File(ctx.filesDir, ARCHIVO).writeText(texto.trim() + "\n")
            estado.value = e
            Engine.db.anotar("licencia", "Licencia cargada: ${e.datos.cliente}, vence ${e.datos.vence}")
        } else {
            Engine.db.anotar("licencia", "Licencia rechazada: " + describir(e))
        }
        return e
    }

    fun quitar(ctx: Context) {
        File(ctx.filesDir, ARCHIVO).delete()
        verificar(ctx)
    }

    fun permiteCaptura(pasadasDelEvento: Int): Boolean = when (estado.value) {
        is Estado.SinControl, is Estado.Valida -> true
        else -> pasadasDelEvento < DEMO_PASADAS
    }

    fun mensajeBloqueo(): String =
        describir(estado.value) + ". Modo demostración: máximo $DEMO_PASADAS pasadas por evento. " +
            "Carga una licencia en Ajustes → Licencia."

    fun describir(e: Estado): String = when (e) {
        is Estado.SinControl -> "Compilación de desarrollo (sin control de licencia)"
        is Estado.SinLicencia -> "Sin licencia"
        is Estado.Valida -> "Licencia válida · ${e.datos.cliente} · " +
            (if (e.diasRestantes == null) "perpetua" else "vence ${e.datos.vence} (${e.diasRestantes} días)")
        is Estado.Vencida -> "La licencia venció el ${e.datos.vence}"
        is Estado.OtroTelefono -> "La licencia es de otro teléfono (${e.datos.telefono})"
        is Estado.Invalida -> "Licencia no válida: ${e.motivo}"
        is Estado.RelojAlterado -> "La fecha del teléfono parece atrasada a propósito. Corrígela y vuelve a abrir la app"
    }

    // ───────────────────────────────────────────

    fun evaluar(ctx: Context, texto: String?): Estado {
        if (BuildConfig.LICENSE_PUBLIC_KEY.isBlank()) return Estado.SinControl
        if (relojAlterado(ctx)) return Estado.RelojAlterado
        if (texto.isNullOrBlank()) return Estado.SinLicencia
        return evaluarTexto(texto, BuildConfig.LICENSE_PUBLIC_KEY, codigoTelefono(ctx), System.currentTimeMillis())
    }

    /** Sin dependencias de Android, para poder probarla en la PC. */
    fun evaluarTexto(texto: String, clavePublicaB64: String, telefono: String, ahora: Long): Estado {
        val lineas = texto.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lineas.size < 3 || lineas[0] != "NXCLIC1") return Estado.Invalida("el archivo no es una licencia de NexCrono")
        val datosBytes: ByteArray
        val firma: ByteArray
        try {
            datosBytes = Base64.getDecoder().decode(lineas[1])
            firma = Base64.getDecoder().decode(lineas[2])
        } catch (e: IllegalArgumentException) {
            return Estado.Invalida("archivo dañado")
        }
        val ok = try {
            val clave = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(clavePublicaB64)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(clave)
                update(datosBytes)
                verify(firma)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) return Estado.Invalida("la firma no corresponde (archivo modificado o de otro emisor)")

        val campos = String(datosBytes, Charsets.UTF_8).lines()
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
            .toMap()
        if (campos["app"] != "pistola") return Estado.Invalida("es de otra aplicación")
        val d = Datos(
            cliente = campos["cliente"] ?: "",
            telefono = campos["telefono"] ?: "",
            plan = campos["plan"] ?: "",
            emitida = campos["emitida"] ?: "",
            vence = campos["vence"] ?: "",
            id = campos["id"] ?: "",
        )
        if (!d.telefono.equals(telefono, ignoreCase = true)) return Estado.OtroTelefono(d)
        if (d.vence.equals("perpetua", ignoreCase = true)) return Estado.Valida(d, null)
        val fin = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.vence)!!.time + TimeUnit.DAYS.toMillis(1)   // vale todo el último día
        } catch (e: Exception) {
            return Estado.Invalida("fecha de vencimiento ilegible")
        }
        if (ahora >= fin) return Estado.Vencida(d)
        return Estado.Valida(d, TimeUnit.MILLISECONDS.toDays(fin - ahora))
    }

    /** Detecta si la fecha del teléfono se atrasó para estirar la licencia. */
    private fun relojAlterado(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val max = p.getLong("maxVisto", 0L)
        if (max > 0 && ahora < max - TimeUnit.HOURS.toMillis(6)) return true
        if (ahora > max) p.edit().putLong("maxVisto", ahora).apply()
        return false
    }

    fun hoy(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
