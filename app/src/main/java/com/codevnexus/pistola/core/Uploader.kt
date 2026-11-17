package com.codevnexus.pistola.core

import com.codevnexus.pistola.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Comunicación con NexCrono. Contrato completo en docs/INSTRUCCIONES_NEXCRONO.md
 *   POST {servidor}/api/pistola/vincular      (sin token, con código de un solo uso)
 *   GET  {servidor}/api/pistola/hora
 *   GET  {servidor}/api/pistola/eventos
 *   GET  {servidor}/api/pistola/inscritos?evento=ID
 *   POST {servidor}/api/pistola/lecturas
 * Todas (menos vincular) con  Authorization: Bearer <token del dispositivo>  y  X-Dispositivo: <id>.
 */
object Uploader {

    private const val P = "/api/pistola"
    private var ultimoIntentoHora = 0L
    private var ultimaDescargaInscritos = 0L
    private val iso = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    class NoAutorizado : Exception("Este teléfono no está autorizado o fue revocado en NexCrono. Vuelve a vincularlo.")

    private fun base(): String = Engine.ajustes.value.servidor.trim().trimEnd('/')

    /** Se llama cada 3 s desde el motor. */
    fun ciclo() {
        val a = Engine.ajustes.value
        if (!a.vinculado) {
            Engine.servidor.value = Servidor(
                if (a.servidor.isBlank()) "Sin NexCrono vinculado (datos guardados en el teléfono)"
                else "Falta vincular este teléfono con NexCrono"
            )
            return
        }
        val ahora = System.currentTimeMillis()
        if (Engine.desfaseReloj == null && ahora - ultimoIntentoHora > 30_000) sincronizarHora()
        if (a.autoEnvio) enviarPendientes()
        if (!a.eventoLocal && ahora - ultimaDescargaInscritos > 5 * 60_000) {
            ultimaDescargaInscritos = ahora
            try { descargarInscritos() } catch (_: Exception) { }
        }
    }

    // ───────────── Vincular ─────────────

    /** Canjea el código del QR por un token propio de este teléfono. */
    fun vincular(v: Vinculo): String {
        val a0 = Engine.ajustes.value
        val body = JSONObject().apply {
            put("codigo", v.codigo)
            put("dispositivoId", a0.dispositivoId)
            put("dispositivo", a0.dispositivo)
            put("app", "NexCrono Pistola")
            put("version", BuildConfig.VERSION_NAME)
        }
        val (codigo, texto) = peticion("POST", v.url + "$P/vincular", body.toString(), conToken = false)
        if (codigo == 401 || codigo == 403 || codigo == 404 || codigo == 410) {
            throw Exception("El código no es válido o ya venció. Genera un QR nuevo en NexCrono.")
        }
        if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
        val r = JSONObject(texto)
        val token = r.optString("token")
        if (token.isBlank()) throw Exception("NexCrono no devolvió un token")
        var a = a0.copy(
            servidor = v.url,
            token = token,
            empresa = r.optString("empresa", a0.empresa),
        )
        r.optString("punto").takeIf { it.isNotBlank() }?.let { a = a.copy(punto = it) } ?: v.punto?.let { a = a.copy(punto = it) }
        r.optString("modo").uppercase().takeIf { it == Modo.SALIDA || it == Modo.CONTROL }?.let { a = a.copy(modo = it) }
            ?: v.modo?.let { a = a.copy(modo = it) }
        Engine.guardarAjustes(a)
        Engine.desfaseReloj = null

        val ev = r.optJSONObject("evento")
        val evId = ev?.optString("id")?.takeIf { it.isNotBlank() } ?: v.eventoId
        val evNombre = ev?.optString("nombre")?.takeIf { it.isNotBlank() } ?: v.eventoNombre ?: evId
        if (evId != null) {
            Engine.cambiarEvento(Evento(evId, evNombre ?: evId, ev?.optString("fecha") ?: "", false))
        }
        val hora = sincronizarHora()
        val ins = if (evId != null) try { descargarInscritos() } catch (e: Exception) { "Inscritos: ${e.message}" } else ""
        return listOf("Teléfono vinculado con ${a.empresa.ifBlank { "NexCrono" }}", hora, ins)
            .filter { it.isNotBlank() }.joinToString("\n")
    }

    fun desvincular() {
        Engine.guardarAjustes(Engine.ajustes.value.copy(token = "", empresa = ""))
        Engine.desfaseReloj = null
    }

    // ───────────── Hora ─────────────

    @Synchronized
    fun sincronizarHora(): String {
        ultimoIntentoHora = System.currentTimeMillis()
        if (base().isEmpty()) return "Configura la dirección de NexCrono"
        return try {
            val t0 = System.currentTimeMillis()
            val (codigo, cuerpo) = peticion("GET", base() + "$P/hora", null)
            val t1 = System.currentTimeMillis()
            if (codigo == 401 || codigo == 403) throw NoAutorizado()
            if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
            val servidorMs = cuerpo.trim().toLongOrNull() ?: JSONObject(cuerpo).getLong("epochMs")
            val desfase = servidorMs - (t0 + t1) / 2
            Engine.desfaseReloj = desfase
            val msg = "Hora sincronizada (diferencia $desfase ms, red ${t1 - t0} ms)"
            Engine.servidor.value = Engine.servidor.value.copy(mensaje = msg, ok = true, desfaseMs = desfase)
            msg
        } catch (e: Exception) {
            val msg = "Sin sincronizar hora: ${e.message ?: e.javaClass.simpleName}"
            Engine.servidor.value = Engine.servidor.value.copy(mensaje = msg, ok = false)
            msg
        }
    }

    // ───────────── Eventos e inscritos ─────────────

    fun eventos(): List<Evento> {
        val (codigo, texto) = peticion("GET", base() + "$P/eventos", null)
        if (codigo == 401 || codigo == 403) throw NoAutorizado()
        if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
        val arr = if (texto.trim().startsWith("[")) JSONArray(texto) else JSONObject(texto).getJSONArray("eventos")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Evento(o.getString("id"), o.optString("nombre", o.getString("id")), o.optString("fecha"), false)
        }
    }

    fun descargarInscritos(): String {
        val a = Engine.ajustes.value
        if (!a.vinculado) throw Exception("Primero vincula el teléfono con NexCrono")
        if (a.eventoLocal) throw Exception("El evento actual es local; elige un evento de NexCrono")
        val ev = a.eventoId
        val (codigo, texto) = peticion("GET", base() + "$P/inscritos?evento=" + URLEncoder.encode(ev, "UTF-8"), null)
        if (codigo == 401 || codigo == 403) throw NoAutorizado()
        if (codigo !in 200..299) throw Exception("NexCrono respondió $codigo")
        val arr = if (texto.trim().startsWith("[")) JSONArray(texto) else JSONObject(texto).getJSONArray("inscritos")
        val lista = (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val dorsal = o.optString("dorsal").trim()
            if (dorsal.isEmpty()) null else Inscrito(
                dorsal = dorsal,
                chip = o.optString("chip").trim(),
                epc = o.optString("epc").trim().uppercase(),
                nombre = o.optString("nombre").trim(),
                categoria = o.optString("categoria").trim(),
                extra = listOf(o.optString("genero"), o.optString("club")).filter { s -> s.isNotBlank() }.joinToString(" · "),
            )
        }
        Engine.guardarInscritos(ev, lista)
        ultimaDescargaInscritos = System.currentTimeMillis()
        return "Inscritos actualizados: ${lista.size}"
    }

    // ───────────── Lecturas ─────────────

    @Synchronized
    fun enviarPendientes(): String {
        val a0 = Engine.ajustes.value
        if (!a0.vinculado) return "Primero vincula el teléfono con NexCrono"
        var total = 0
        while (true) {
            val lote = Engine.db.pendientes(200)
            if (lote.isEmpty()) break
            val a = Engine.ajustes.value
            val body = JSONObject().apply {
                put("dispositivoId", a.dispositivoId)
                put("dispositivo", a.dispositivo)
                put("operador", a.operador)
                put("app", "NexCrono Pistola " + BuildConfig.VERSION_NAME)
                put("enviado", fecha(System.currentTimeMillis()))
                put("lecturas", JSONArray().apply {
                    for (p in lote) put(JSONObject().apply {
                        put("id", p.id)
                        put("evento", p.evento)
                        put("dorsal", p.dorsal ?: JSONObject.NULL)
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
                val (codigo, _) = peticion("POST", base() + "$P/lecturas", body.toString())
                if (codigo == 401 || codigo == 403) throw NoAutorizado()
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

    // ───────────── HTTP ─────────────

    fun peticion(metodo: String, url: String, cuerpo: String?, conToken: Boolean = true): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 6000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            val a = Engine.ajustes.value
            c.setRequestProperty("X-Dispositivo", a.dispositivoId)
            if (conToken && a.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + a.token)
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
