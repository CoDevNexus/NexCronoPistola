package com.codevnexus.pistola.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Envío a NexCrono. Contrato (NexCrono debe implementarlo):
 *   GET  {servidor}/api/pistola/hora      → {"epochMs": 1763380000000}
 *   POST {servidor}/api/pistola/lecturas  → body con "lecturas"; responder 2xx cuando se guardaron.
 * Cada lectura lleva un "id" único: si llega repetida, NexCrono debe ignorarla.
 */
object Uploader {

    private const val RUTA_HORA = "/api/pistola/hora"
    private const val RUTA_LECTURAS = "/api/pistola/lecturas"
    private var ultimoIntentoHora = 0L
    private val iso = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    private fun base(): String = Engine.ajustes.value.servidor.trim().trimEnd('/')

    /** Se llama cada 3 s desde el motor. */
    fun ciclo() {
        val a = Engine.ajustes.value
        if (base().isEmpty()) {
            Engine.servidor.value = Servidor("Sin servidor configurado")
            return
        }
        if (Engine.desfaseReloj == null && System.currentTimeMillis() - ultimoIntentoHora > 30_000) {
            sincronizarHora()
        }
        if (a.autoEnvio) enviarPendientes()
    }

    @Synchronized
    fun sincronizarHora(): String {
        ultimoIntentoHora = System.currentTimeMillis()
        if (base().isEmpty()) return "Configura la dirección de NexCrono"
        return try {
            val t0 = System.currentTimeMillis()
            val (codigo, cuerpo) = peticion("GET", base() + RUTA_HORA, null)
            val t1 = System.currentTimeMillis()
            if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
            val servidorMs = cuerpo.trim().toLongOrNull() ?: JSONObject(cuerpo).getLong("epochMs")
            val desfase = servidorMs - (t0 + t1) / 2
            Engine.desfaseReloj = desfase
            val msg = "Hora sincronizada (diferencia ${desfase} ms, red ${t1 - t0} ms)"
            Engine.servidor.value = Engine.servidor.value.copy(mensaje = msg, ok = true, desfaseMs = desfase)
            msg
        } catch (e: Exception) {
            val msg = "Sin sincronizar hora: ${e.message ?: e.javaClass.simpleName}"
            Engine.servidor.value = Engine.servidor.value.copy(mensaje = msg, ok = false)
            msg
        }
    }

    @Synchronized
    fun enviarPendientes(): String {
        if (base().isEmpty()) return "Configura la dirección de NexCrono"
        var total = 0
        while (true) {
            val lote = Engine.db.pendientes(200)
            if (lote.isEmpty()) break
            val a = Engine.ajustes.value
            val body = JSONObject().apply {
                put("dispositivo", a.dispositivo)
                put("operador", a.operador)
                put("punto", a.nombrePunto)
                put("modo", a.modo)
                put("app", "NexCrono Pistola 1.0.0")
                put("enviado", fecha(System.currentTimeMillis()))
                put("lecturas", JSONArray().apply {
                    for (p in lote) put(JSONObject().apply {
                        put("id", p.id)
                        put("chip", p.chip)
                        put("epc", p.epc)
                        put("punto", p.punto)
                        put("modo", p.modo)
                        put("horaMs", p.hora)
                        put("hora", fecha(p.hora))
                        put("primeraMs", p.primera)
                        put("ultimaMs", p.ultima)
                        put("rssi", p.rssi ?: JSONObject.NULL)
                        put("lecturas", p.lecturas)
                        put("origen", p.origen)
                        put("horaSincronizada", p.sincronizado)
                    })
                })
            }
            try {
                val (codigo, _) = peticion("POST", base() + RUTA_LECTURAS, body.toString())
                if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
                Engine.db.marcarEnviados(lote.map { it.id })
                total += lote.size
                Engine.marcarSucio()
            } catch (e: Exception) {
                val msg = "No se pudo enviar: ${e.message ?: e.javaClass.simpleName}"
                Engine.servidor.value = Engine.servidor.value.copy(mensaje = msg, ok = false)
                return msg
            }
        }
        if (total > 0) {
            Engine.servidor.value = Engine.servidor.value.copy(
                mensaje = "Enviadas $total pasadas", ok = true, ultimoEnvio = System.currentTimeMillis()
            )
        } else if (!Engine.servidor.value.ok && Engine.desfaseReloj != null) {
            Engine.servidor.value = Engine.servidor.value.copy(mensaje = "Conectado a NexCrono", ok = true)
        }
        return if (total > 0) "Enviadas $total pasadas" else "No hay pasadas pendientes"
    }

    private fun peticion(metodo: String, url: String, cuerpo: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            val token = Engine.ajustes.value.token
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val stream = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return codigo to texto
        } finally {
            c.disconnect()
        }
    }

    fun fecha(ms: Long): String = iso.get()!!.format(Date(ms))
}
