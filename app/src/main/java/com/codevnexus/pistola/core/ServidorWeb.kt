package com.codevnexus.pistola.core

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servidor web dentro del teléfono para descargar los archivos de los eventos por WiFi
 * (misma red o el hotspot del propio teléfono). Sin internet.
 *
 * Protección: PIN de 6 dígitos que se muestra en la app (cambia cada vez que se enciende),
 * sesión con cookie HttpOnly, bloqueo de 5 minutos tras 5 PIN errados por dirección IP,
 * solo lectura (no se puede modificar nada desde el navegador).
 */
object ServidorWeb {

    data class EstadoWeb(
        val activo: Boolean = false,
        val url: String = "",
        val pin: String = "",
        val mensaje: String = "Apagado",
    )

    val estado = MutableStateFlow(EstadoWeb())

    @Volatile private var socket: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(4)
    private val azar = SecureRandom()
    private val sesiones = ConcurrentHashMap<String, Long>()          // token → vence
    private val fallos = ConcurrentHashMap<String, Pair<Int, Long>>() // ip → (intentos, bloqueado hasta)
    private const val DURACION_SESION = 8 * 3600_000L

    // ───────────── Encender / apagar ─────────────

    @Synchronized
    fun iniciar(ipForzada: String? = null): EstadoWeb {
        if (socket != null) return estado.value
        val ip = ipForzada ?: ipLocal()
        if (ip == null) {
            estado.value = EstadoWeb(mensaje = "Conecta el teléfono a un WiFi o activa su hotspot")
            return estado.value
        }
        var ss: ServerSocket? = null
        var puerto = 8080
        while (puerto <= 8090 && ss == null) {
            ss = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(puerto)) }
            } catch (e: Exception) {
                puerto++; null
            }
        }
        if (ss == null) {
            estado.value = EstadoWeb(mensaje = "No hay puertos libres (8080–8090)")
            return estado.value
        }
        socket = ss
        sesiones.clear()
        fallos.clear()
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)
        val pin = (100000 + azar.nextInt(900000)).toString()
        estado.value = EstadoWeb(true, "http://$ip:$puerto", pin, "Encendido")
        anotar("Servidor WiFi encendido en http://$ip:$puerto")
        Thread({
            while (true) {
                val s = socket ?: break
                val cliente = try { s.accept() } catch (e: Exception) { break }
                pool.execute { atender(cliente, pin) }
            }
        }, "servidor-web").start()
        return estado.value
    }

    @Synchronized
    fun detener() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        sesiones.clear()
        if (estado.value.activo) anotar("Servidor WiFi apagado")
        estado.value = EstadoWeb()
    }

    /** IPv4 de la red local (WiFi o hotspot). */
    fun ipLocal(): String? {
        val candidatas = ArrayList<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) candidatas += ni.name to (a.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) { }
        // preferir WiFi (wlan) y hotspot (ap / swlan) sobre datos móviles
        return candidatas.sortedBy { (n, _) ->
            when {
                n.startsWith("wlan") -> 0
                n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") -> 1
                else -> 2
            }
        }.firstOrNull()?.second
    }

    private fun anotar(msg: String) {
        try { Engine.db.anotar("wifi", msg) } catch (_: Throwable) { }
    }

    // ───────────── HTTP ─────────────

    private class Peticion(val metodo: String, val ruta: String, val query: Map<String, String>,
                           val cabeceras: Map<String, String>, val cuerpo: String, val ip: String)

    private fun atender(s: Socket, pin: String) {
        s.use { sock ->
            try {
                sock.soTimeout = 15000
                val p = leerPeticion(sock) ?: return
                responder(sock.getOutputStream(), p, pin)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun leerPeticion(s: Socket): Peticion? {
        val inp = BufferedInputStream(s.getInputStream())
        fun linea(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
                if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
                if (b.size() > 8192) return null
                b.write(c)
            }
        }
        val primera = linea() ?: return null
        val partes = primera.split(' ')
        if (partes.size < 2) return null
        val cab = HashMap<String, String>()
        var n = 0
        while (true) {
            val l = linea() ?: break
            if (l.isEmpty()) break
            if (++n > 60) return null
            val i = l.indexOf(':')
            if (i > 0) cab[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val largo = cab["content-length"]?.toIntOrNull() ?: 0
        if (largo > 4096) return null
        val cuerpo = ByteArray(largo)
        var leidos = 0
        while (leidos < largo) {
            val r = inp.read(cuerpo, leidos, largo - leidos)
            if (r < 0) break
            leidos += r
        }
        val destino = partes[1]
        val ruta = destino.substringBefore('?')
        val query = parametros(destino.substringAfter('?', ""))
        return Peticion(partes[0].uppercase(), ruta, query, cab, String(cuerpo, 0, leidos, Charsets.UTF_8),
            s.inetAddress?.hostAddress ?: "-")
    }

    private fun parametros(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun sesionValida(p: Peticion): Boolean {
        val token = p.cabeceras["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("nxs=") }?.removePrefix("nxs=") ?: return false
        val vence = sesiones[token] ?: return false
        if (System.currentTimeMillis() > vence) { sesiones.remove(token); return false }
        return true
    }

    private fun responder(out: OutputStream, p: Peticion, pin: String) {
        when {
            p.ruta == "/login" && p.metodo == "POST" -> login(out, p, pin)
            p.ruta == "/salir" -> {
                p.cabeceras["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("nxs=") }
                    ?.let { sesiones.remove(it.removePrefix("nxs=")) }
                redirigir(out, "/", "nxs=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict")
            }
            !sesionValida(p) -> html(out, 200, paginaLogin(null))
            p.ruta == "/" -> html(out, 200, paginaEventos())
            p.ruta == "/descargar" -> descargar(out, p)
            else -> html(out, 404, plantilla("No encontrado", "<p>Esa página no existe. <a href=\"/\">Volver</a></p>"))
        }
    }

    private fun login(out: OutputStream, p: Peticion, pin: String) {
        val ahora = System.currentTimeMillis()
        val f = fallos[p.ip]
        if (f != null && f.second > ahora) {
            html(out, 429, paginaLogin("Demasiados intentos. Espera unos minutos."))
            return
        }
        val dado = parametros(p.cuerpo)["pin"]?.trim() ?: ""
        if (dado.length == pin.length && java.security.MessageDigest.isEqual(dado.toByteArray(), pin.toByteArray())) {
            fallos.remove(p.ip)
            val token = ByteArray(24).also { azar.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sesiones[token] = ahora + DURACION_SESION
            anotar("Acceso al servidor WiFi desde ${p.ip}")
            redirigir(out, "/", "nxs=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${DURACION_SESION / 1000}")
        } else {
            val n = (f?.first ?: 0) + 1
            fallos[p.ip] = if (n >= 5) 0 to ahora + 5 * 60_000L else n to 0L
            Thread.sleep(800)
            html(out, 401, paginaLogin(if (n >= 5) "Demasiados intentos. Espera 5 minutos." else "PIN incorrecto."))
        }
    }

    private fun descargar(out: OutputStream, p: Peticion) {
        val id = p.query["evento"] ?: ""
        val tipo = p.query["f"] ?: ""
        val e = Engine.db.evento(id)
        if (e == null) {
            html(out, 404, plantilla("No encontrado", "<p>El evento no existe. <a href=\"/\">Volver</a></p>"))
            return
        }
        val (datos, mime, nombre) = when (tipo) {
            "csv" -> Triple(Exportador.pasadasCsv(e), Exportador.MIME_CSV, Exportador.nombreArchivo(e, "pasadas", "csv"))
            "xlsx" -> Triple(Exportador.pasadasXlsx(e), Exportador.MIME_XLSX, Exportador.nombreArchivo(e, "pasadas", "xlsx"))
            "lcsv" -> Triple(Exportador.lecturasCsv(e), Exportador.MIME_CSV, Exportador.nombreArchivo(e, "lecturas", "csv"))
            "pcsv" -> Triple(Exportador.participantesCsv(e), Exportador.MIME_CSV, Exportador.nombreArchivo(e, "participantes", "csv"))
            "pxlsx" -> Triple(Exportador.participantesXlsx(e), Exportador.MIME_XLSX, Exportador.nombreArchivo(e, "participantes", "xlsx"))
            else -> {
                html(out, 400, plantilla("Formato no válido", "<p><a href=\"/\">Volver</a></p>"))
                return
            }
        }
        anotar("Descarga por WiFi: $nombre (${p.ip})")
        val cab = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${datos.size}\r\n" +
            "Content-Disposition: attachment; filename=\"$nombre\"\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        out.write(cab.toByteArray(Charsets.ISO_8859_1))
        out.write(datos)
        out.flush()
    }

    private fun html(out: OutputStream, codigo: Int, cuerpo: String) {
        val b = cuerpo.toByteArray(Charsets.UTF_8)
        val txt = mapOf(200 to "OK", 302 to "Found", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 429 to "Too Many Requests")
        val cab = "HTTP/1.1 $codigo ${txt[codigo] ?: "OK"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${b.size}\r\nCache-Control: no-store\r\nX-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\nConnection: close\r\n\r\n"
        out.write(cab.toByteArray(Charsets.ISO_8859_1))
        out.write(b)
        out.flush()
    }

    private fun redirigir(out: OutputStream, a: String, cookie: String) {
        val cab = "HTTP/1.1 302 Found\r\nLocation: $a\r\nSet-Cookie: $cookie\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(cab.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    // ───────────── Páginas ─────────────

    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun plantilla(titulo: String, cuerpo: String) = """<!DOCTYPE html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${h(titulo)} · NexCrono Pistola</title>
<style>body{margin:0;background:#0b1114;color:#e6eef0;font:15px/1.5 system-ui,sans-serif}main{max-width:900px;margin:0 auto;padding:20px}
h1{font-size:20px;margin:0 0 4px}h1 b{color:#00c2a8}.sub{color:#9fb2b8;font-size:13px;margin:0 0 18px}
.card{background:#1a252a;border-radius:14px;padding:14px 16px;margin-bottom:12px}.n{color:#9fb2b8;font-size:13px}
a.b{display:inline-block;margin:6px 6px 0 0;padding:7px 12px;border-radius:9px;background:#00c2a8;color:#00201b;text-decoration:none;font-weight:700;font-size:13px}
a.s{background:transparent;color:#e6eef0;border:1px solid #3a4a50;font-weight:500}input{font:inherit;padding:10px;border-radius:9px;border:1px solid #2b3a40;background:#0f171a;color:#e6eef0;width:160px;letter-spacing:4px}
button{font:inherit;padding:10px 16px;border:0;border-radius:9px;background:#00c2a8;color:#00201b;font-weight:700}.err{color:#ff5370}
.act{color:#ff8a3d;font-size:12px;font-weight:700}footer{color:#9fb2b8;font-size:11px;margin-top:24px}</style></head>
<body><main><h1>NexCrono <b>Pistola</b></h1>$cuerpo<footer>NexCrono · CodevNexus · codevnexus.tech</footer></main></body></html>"""

    private fun paginaLogin(error: String?) = plantilla(
        "Acceso",
        "<p class=\"sub\">Escribe el PIN que aparece en la app (Eventos → Compartir por WiFi).</p>" +
            "<div class=\"card\"><form method=\"post\" action=\"/login\">" +
            "<input name=\"pin\" inputmode=\"numeric\" maxlength=\"6\" autocomplete=\"off\" autofocus> <button>Entrar</button></form>" +
            (error?.let { "<p class=\"err\">${h(it)}</p>" } ?: "") + "</div>"
    )

    private fun paginaEventos(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val actual = Engine.ajustes.value.eventoId
        val eventos = Engine.db.eventosResumen()
        val sb = StringBuilder("<p class=\"sub\">Eventos guardados en el teléfono. Archivos con el número de chip ya convertido. El Excel completo trae las hojas Pasadas y Lecturas (todas las marcaciones).</p>")
        if (eventos.isEmpty()) sb.append("<div class=\"card\">No hay eventos.</div>")
        for (r in eventos) {
            val e = r.evento
            val id = java.net.URLEncoder.encode(e.id, "UTF-8")
            sb.append("<div class=\"card\"><b>${h(e.nombre)}</b>")
            if (e.id == actual) sb.append(" <span class=\"act\">ACTUAL</span>")
            sb.append("<div class=\"n\">${h(listOf(e.fecha, e.lugar, if (e.local) "Local" else "NexCrono").filter { it.isNotBlank() }.joinToString(" · "))}</div>")
            sb.append("<div class=\"n\">${r.pasadas} pasadas · ${r.lecturas} lecturas · ${r.chips} chips · ${r.inscritos} participantes" +
                (r.ultima?.let { " · última lectura ${f.format(Date(it))}" } ?: "") + "</div>")
            sb.append("<a class=\"b\" href=\"/descargar?evento=$id&f=xlsx\">Excel completo</a>")
            sb.append("<a class=\"b s\" href=\"/descargar?evento=$id&f=csv\">Pasadas CSV</a>")
            if (r.lecturas > 0) sb.append("<a class=\"b s\" href=\"/descargar?evento=$id&f=lcsv\">Todas las lecturas CSV</a>")
            if (r.inscritos > 0) {
                sb.append("<a class=\"b\" href=\"/descargar?evento=$id&f=pxlsx\">Participantes Excel</a>")
                sb.append("<a class=\"b s\" href=\"/descargar?evento=$id&f=pcsv\">Participantes CSV</a>")
            }
            sb.append("</div>")
        }
        sb.append("<p><a class=\"b s\" href=\"/salir\">Salir</a></p>")
        return plantilla("Eventos", sb.toString())
    }
}
