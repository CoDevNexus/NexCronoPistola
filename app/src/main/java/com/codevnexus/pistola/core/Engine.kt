package com.codevnexus.pistola.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.codevnexus.pistola.MainActivity
import com.codevnexus.pistola.R
import com.codevnexus.pistola.driver.ConfigPistola
import com.codevnexus.pistola.driver.DriverListener
import com.codevnexus.pistola.driver.LecturaMemoria
import com.codevnexus.pistola.driver.Drivers
import com.codevnexus.pistola.driver.RfidDriver
import com.codevnexus.pistola.service.LectorService
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Motor de la app. Vive mientras el proceso vive; el servicio en primer plano mantiene vivo el proceso
 * (y el Bluetooth) aunque la pantalla esté bloqueada.
 */
@SuppressLint("StaticFieldLeak")
object Engine {

    const val CANAL = "lector"
    const val NOTIF_ID = 7

    lateinit var app: Context
        private set
    lateinit var db: Db
        private set

    val ajustes = MutableStateFlow(Ajustes())
    val conexion = MutableStateFlow(Conexion())
    val bateria = MutableStateFlow<Int?>(null)
    val info = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val leyendo = MutableStateFlow(false)
    val recientes = MutableStateFlow<List<Pasada>>(emptyList())
    val stats = MutableStateFlow(Estadisticas())
    val control = MutableStateFlow(Control())
    val servidor = MutableStateFlow(Servidor())
    val consola = MutableStateFlow<List<String>>(emptyList())
    val ultimaEpc = MutableStateFlow<String?>(null)
    val tablaTam = MutableStateFlow(0)
    val inscritosTam = MutableStateFlow(0)
    val aviso = MutableStateFlow<Aviso?>(null)
    val vinculoPendiente = MutableStateFlow<Vinculo?>(null)
    val actualizacion = MutableStateFlow<Actualizacion?>(null)
    /** Estado de la descarga de memoria/SD: null = sin actividad. */
    val memoria = MutableStateFlow<String?>(null)
    val grabacion = MutableStateFlow(Grabacion())
    @Volatile private var epcEnGrabacion: String? = null

    @Volatile var desfaseReloj: Long? = null
    @Volatile private var tabla: Map<String, String> = emptyMap()

    // inscritos del evento actual
    @Volatile private var inscritos: List<Inscrito> = emptyList()
    @Volatile private var porEpc: Map<String, Inscrito> = emptyMap()
    @Volatile private var porChip: Map<String, Inscrito> = emptyMap()
    @Volatile private var porDorsal: Map<String, Inscrito> = emptyMap()

    private var iniciado = false
    private val sched = Executors.newScheduledThreadPool(2)
    private val abiertas = HashMap<String, Pasada>()
    @Volatile private var driver: RfidDriver? = null
    private var pistola: BluetoothDevice? = null
    private var pistolaNombre = ""
    @Volatile private var quiereConexion = false
    private var ultimoIntento = 0L
    @Volatile private var sucio = true
    private var ultimoTextoNotif = ""
    private var tick = 0L
    private var tono: ToneGenerator? = null

    @Synchronized
    fun init(ctx: Context) {
        if (iniciado) return
        app = ctx.applicationContext
        db = Db(app)
        var a = Ajustes.cargar(app)
        if (a.eventoId.isBlank()) {
            val e = Evento("local-" + UUID.randomUUID().toString().take(8), Ajustes.nombreEventoLocal(), hoy(), true)
            db.guardarEvento(e)
            a = a.copy(eventoId = e.id, eventoNombre = e.nombre, eventoLocal = true)
            Ajustes.guardar(app, a)
        }
        ajustes.value = a
        tabla = db.cargarMapa()
        tablaTam.value = tabla.size
        cargarInscritos()
        // pasadas que quedaron abiertas si la app se cerró: se reabren para cerrarlas bien
        for (p in db.abiertas()) abiertas[p.epc] = p
        crearCanal()
        sched.scheduleWithFixedDelay({ seguro { cadaSegundo() } }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ seguro { if (sucio) refrescar() } }, 0, 300, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ seguro { Uploader.ciclo() } }, 3, 3, TimeUnit.SECONDS)
        iniciado = true
    }

    private inline fun seguro(f: () -> Unit) {
        try { f() } catch (t: Throwable) { log("! " + (t.message ?: t.javaClass.simpleName)) }
    }

    fun enSegundoPlano(f: () -> Unit) {
        sched.execute { seguro(f) }
    }

    private fun hoy() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ───────────────────────── Ajustes ─────────────────────────

    fun guardarAjustes(a: Ajustes) {
        val antes = ajustes.value
        ajustes.value = a
        Ajustes.guardar(app, a)
        if (a.chipModo != antes.chipModo || a.chipDigitos != antes.chipDigitos) reasignarPasadas()
        if (a.potencia != antes.potencia && a.configurarPistola) driver?.fijarPotencia(a.potencia)
        if (a.servidor != antes.servidor) desfaseReloj = null
        sucio = true
    }

    fun importarTabla(m: Map<String, String>) {
        db.guardarMapa(m)
        tabla = m
        tablaTam.value = m.size
        reasignarPasadas()
    }

    fun tablaParaVista(): Map<String, String> = tabla

    fun decodificar(epc: String) = ChipDecoder.decodificar(epc, ajustes.value, tabla)

    private fun buscarInscrito(epc: String, chip: String): Inscrito? =
        porEpc[epc.uppercase()] ?: porChip[chip] ?: porChip[chip.trimStart('0')]

    private fun reasignarPasadas() {
        synchronized(this) {
            db.reasignar(ajustes.value.eventoId, { decodificar(it) }, { e, c -> buscarInscrito(e, c) })
            for (p in abiertas.values) {
                p.chip = decodificar(p.epc)
                val i = buscarInscrito(p.epc, p.chip)
                p.dorsal = i?.dorsal; p.nombre = i?.nombre; p.categoria = i?.categoria
            }
        }
        sucio = true
    }

    // ───────────────────────── Eventos e inscritos ─────────────────────────

    private fun cargarInscritos() {
        val l = db.inscritos(ajustes.value.eventoId)
        inscritos = l
        porDorsal = l.associateBy { it.dorsal }
        porEpc = l.filter { it.epc.isNotBlank() }.associateBy { it.epc.uppercase() }
        porChip = l.filter { it.chip.isNotBlank() }.flatMap { listOf(it.chip to it, it.chip.trimStart('0') to it) }.toMap()
        inscritosTam.value = l.size
    }

    fun guardarInscritos(evento: String, lista: List<Inscrito>) {
        db.guardarInscritos(evento, lista)
        if (evento == ajustes.value.eventoId) {
            cargarInscritos()
            reasignarPasadas()
        }
    }

    fun cambiarEvento(e: Evento) {
        cerrarTodas()
        db.guardarEvento(e)
        guardarAjustes(ajustes.value.copy(eventoId = e.id, eventoNombre = e.nombre, eventoLocal = e.local))
        cargarInscritos()
        sucio = true
    }

    fun nuevoEventoLocal(nombre: String) {
        val e = Evento(
            "local-" + UUID.randomUUID().toString().take(8),
            nombre.ifBlank { Ajustes.nombreEventoLocal() }, hoy(), true
        )
        cambiarEvento(e)
    }

    fun buscarPorDorsal(dorsal: String): Inscrito? = porDorsal[dorsal.trim()]

    // ───────────────────────── Conexión ─────────────────────────

    fun conectar(device: BluetoothDevice, nombre: String) {
        desconectar(detenerServicio = false)
        pistola = device
        pistolaNombre = nombre
        quiereConexion = true
        guardarAjustes(ajustes.value.copy(ultimaPistola = device.address))
        app.startForegroundService(Intent(app, LectorService::class.java))
        abrir()
    }

    /** Tras un reinicio del servicio por el sistema: vuelve a conectar con la última pistola. */
    @SuppressLint("MissingPermission")
    fun reconectarUltima(): Boolean {
        if (quiereConexion) return true
        val dir = ajustes.value.ultimaPistola
        if (dir.isBlank()) return false
        return try {
            val bt = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            if (!bt.isEnabled) return false
            val dev = bt.getRemoteDevice(dir)
            pistola = dev
            pistolaNombre = dev.name ?: dir
            quiereConexion = true
            abrir()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun abrir() {
        val dev = pistola ?: return
        val oyente = Oyente()
        val d = Drivers.crear(ajustes.value.driverId, oyente)
        oyente.dueno = d
        driver = d
        ultimoIntento = System.currentTimeMillis()
        val reintento = conexion.value.estado == Estado.RECONECTANDO
        conexion.value = Conexion(
            if (reintento) Estado.RECONECTANDO else Estado.CONECTANDO, pistolaNombre,
            if (reintento) "Reconectando…" else "Conectando…", d.soportaComandos
        )
        d.conectar(dev)
    }

    fun desconectar(detenerServicio: Boolean = true) {
        quiereConexion = false
        leyendo.value = false
        driver?.desconectar()
        driver = null
        bateria.value = null
        conexion.value = Conexion()
        if (detenerServicio && iniciado) app.stopService(Intent(app, LectorService::class.java))
    }

    fun iniciarLectura() {
        val d = driver ?: return
        if (!d.soportaComandos) return
        leyendo.value = true
        d.iniciarContinuo()
    }

    fun detenerLectura() {
        leyendo.value = false
        driver?.detenerContinuo()
    }

    fun actualizarInfo() {
        info.value = emptyList()
        driver?.pedirInfo()
        driver?.pedirBateria()
    }

    fun leerMemoria(): Boolean {
        val d = driver ?: return false
        memoria.value = "Leyendo la memoria de la pistola…"
        val ok = d.leerMemoria()
        if (!ok) memoria.value = null
        return ok
    }

    fun borrarMemoria(): Boolean {
        val d = driver ?: return false
        memoria.value = "Borrando la memoria de la pistola…"
        return d.borrarMemoria()
    }

    private fun configActual(): ConfigPistola {
        val a = ajustes.value
        return ConfigPistola(a.potencia, a.gatilloContinuo, a.pitidoPistola) { ahoraCorregido() }
    }

    /** Graba la configuración en AUTO.TXT para que se aplique aunque la pistola se duerma y despierte sin el teléfono. */
    fun grabarAutorun(): Boolean {
        val d = driver ?: return false
        if (!d.grabarAutorun(configActual())) return false
        memoria.value = "Grabando la configuración en la pistola…"
        return true
    }

    // ───────────── Grabar número en chips ─────────────

    fun leerChipCercano(): Boolean {
        val d = driver ?: return false
        leyendo.value = false
        if (!d.leerChipCercano()) return false
        grabacion.value = grabacion.value.copy(ocupado = true, ok = null, mensaje = "Leyendo el chip más cercano…")
        return true
    }

    /** EPC nuevo: prefijo hex + ceros + número, del mismo largo que el EPC actual. */
    fun epcNuevo(actual: String, numero: String, prefijo: String): String? {
        val p = prefijo.uppercase().filter { it in "0123456789ABCDEF" }
        val n = numero.filter(Char::isDigit).trimStart('0').ifEmpty { if (numero.isNotEmpty()) "0" else "" }
        if (n.isEmpty()) return null
        val relleno = actual.length - p.length - n.length
        if (relleno < 0) return null
        return p + "0".repeat(relleno) + n
    }

    /** @return null si empezó a grabar, o el motivo por el que no se puede. */
    fun grabarChip(numero: String, prefijo: String): String? {
        val d = driver ?: return "Conecta la pistola"
        val g = grabacion.value
        val actual = g.epcActual ?: return "Primero lee el chip"
        val nuevo = epcNuevo(actual, numero, prefijo) ?: return "El número no cabe en el EPC de este chip"
        if (nuevo == actual) return "El chip ya tiene ese número"
        if (!d.grabarEpc(actual, nuevo)) return "La pistola está ocupada o el EPC no es válido"
        epcEnGrabacion = nuevo
        grabacion.value = g.copy(ocupado = true, ok = null, mensaje = "Grabando $nuevo…")
        return null
    }

    private fun alLeerChipCercano(epc: String?, rssi: Int?) {
        val esperado = epcEnGrabacion
        if (esperado != null) {                       // verificación tras grabar
            epcEnGrabacion = null
            grabacion.value = if (epc == esperado) {
                Grabacion(
                    ok = true, verificado = esperado,
                    mensaje = "✔ Chip grabado y verificado. La app lo mostrará como ${decodificar(esperado)}.",
                )
            } else {
                Grabacion(
                    ok = false,
                    mensaje = "Se escribió, pero al verificar se leyó ${epc ?: "ningún chip"}. Lee el chip otra vez para revisarlo.",
                )
            }
            return
        }
        grabacion.value = Grabacion(
            epcActual = epc, rssi = rssi,
            mensaje = if (epc == null) "No hay ningún chip cerca de la antena" else "Chip leído",
        )
    }

    private fun alGrabar(ok: Boolean, mensaje: String) {
        if (ok) {
            grabacion.value = grabacion.value.copy(ocupado = true, mensaje = "Grabado. Verificando…")
            sched.schedule({ seguro { if (driver?.leerChipCercano() != true) alLeerChipCercano(null, null) } }, 300, TimeUnit.MILLISECONDS)
        } else {
            epcEnGrabacion = null
            grabacion.value = grabacion.value.copy(ocupado = false, ok = false, mensaje = mensaje)
        }
    }

    /**
     * Convierte las lecturas guardadas en la pistola en pasadas (misma regla de ventana que en vivo)
     * y las agrega al evento actual. Un id determinístico evita duplicados si se importa dos veces.
     */
    private fun importarMemoria(lecturas: List<LecturaMemoria>): String {
        if (lecturas.isEmpty()) return "No había lecturas de chips en la memoria"
        val a = ajustes.value
        val ventana = a.ventanaSeg.coerceAtLeast(1) * 1000L
        var nuevas = 0
        var repetidas = 0
        synchronized(this) {
            for ((epc, lista) in lecturas.groupBy { it.epc }) {
                val tiempos = lista.map { it.ts }.sorted()
                var ini = 0
                for (k in tiempos.indices) {
                    val fin = k == tiempos.lastIndex || tiempos[k + 1] - tiempos[k] > ventana
                    if (!fin) continue
                    val primera = tiempos[ini]
                    val ultima = tiempos[k]
                    val id = UUID.nameUUIDFromBytes("mem|${a.dispositivoId}|$epc|$primera".toByteArray()).toString()
                    if (db.existe(id)) {
                        repetidas++
                    } else {
                        val chip = decodificar(epc)
                        val i = buscarInscrito(epc, chip)
                        val p = Pasada(
                            id = id, evento = a.eventoId, epc = epc, chip = chip,
                            dorsal = i?.dorsal, nombre = i?.nombre, categoria = i?.categoria,
                            punto = a.nombrePunto, modo = a.modo, dispositivo = a.dispositivo,
                            primera = primera, ultima = ultima, mejorTs = primera, rssi = null,
                            lecturas = k - ini + 1, hora = primera, cerrada = true, enviado = false,
                            origen = "memoria", sincronizado = desfaseReloj != null,
                        )
                        p.hora = horaSegunRegla(p)
                        db.guardar(p)
                        nuevas++
                    }
                    ini = k + 1
                }
            }
        }
        sucio = true
        return "Importadas $nuevas pasadas al evento «${a.eventoNombre}»" +
            (if (repetidas > 0) " ($repetidas ya estaban)" else "") + "."
    }

    fun enviarComando(cmd: String) {
        if (cmd.isNotBlank()) driver?.enviarComando(cmd)
    }

    // ───────────────────────── Eventos del driver ─────────────────────────

    /** Solo deja pasar eventos del driver vigente (evita que una conexión vieja pise a la nueva). */
    private class Oyente : DriverListener {
        @Volatile var dueno: RfidDriver? = null
        private fun vigente() = dueno != null && dueno === driver
        override fun onConectado() { if (vigente()) Engine.onConectado() }
        override fun onDesconectado(motivo: String?, porUsuario: Boolean) {
            if (vigente()) Engine.onDesconectado(motivo, porUsuario)
        }
        override fun onTag(epc: String, rssi: Int?) { if (vigente()) Engine.registrarLectura(epc, rssi) }
        override fun onCodigoBarras(codigo: String) { if (vigente()) Engine.onCodigoBarras(codigo) }
        override fun onBateria(porcentaje: Int) { if (vigente()) Engine.bateria.value = porcentaje.coerceIn(0, 100) }
        override fun onInfo(clave: String, valor: String) {
            if (vigente()) Engine.info.value = Engine.info.value.filter { it.first != clave } + (clave to valor)
        }
        override fun onLinea(linea: String, saliente: Boolean) { Engine.log((if (saliente) "→ " else "← ") + linea) }
        override fun onError(mensaje: String) { if (vigente()) Engine.onError(mensaje) }
        override fun onChipCercano(epc: String?, rssi: Int?) { if (vigente()) Engine.alLeerChipCercano(epc, rssi) }
        override fun onGrabacion(ok: Boolean, mensaje: String) { if (vigente()) Engine.alGrabar(ok, mensaje) }
        override fun onMemoria(lecturas: List<LecturaMemoria>, mensaje: String) {
            if (!vigente()) return
            Engine.memoria.value = if (lecturas.isEmpty()) mensaje else mensaje + "\n" + Engine.importarMemoria(lecturas)
        }
    }

    private fun onConectado() {
        conexion.value = Conexion(Estado.CONECTADO, pistolaNombre, "Conectada", driver?.soportaComandos ?: true)
        val a = ajustes.value
        if (a.configurarPistola) {
            driver?.configurar(configActual())
        }
        actualizarInfo()
        // comandos de configuración propios (p. ej. comportamiento del gatillo)
        ajustes.value.comandosConectar.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { driver?.enviarComando(it) }
        sucio = true
    }

    private fun onDesconectado(motivo: String?, porUsuario: Boolean) {
        leyendo.value = false
        bateria.value = null
        if (quiereConexion && !porUsuario) {
            conexion.value = Conexion(
                Estado.RECONECTANDO, pistolaNombre,
                "Se perdió la conexión (${motivo ?: "sin respuesta"}). Reintentando…"
            )
        } else if (!quiereConexion) {
            conexion.value = Conexion()
        }
    }

    private fun onError(mensaje: String) {
        leyendo.value = false
        log("! $mensaje")
        conexion.value = conexion.value.copy(mensaje = mensaje)
    }

    private fun onCodigoBarras(codigo: String) {
        val c = codigo.trim()
        val v = Vinculo.desde(c)
        if (v != null) {
            vinculoPendiente.value = v
            return
        }
        val dorsal = c.filter { it.isLetterOrDigit() }
        if (dorsal.isNotEmpty() && dorsal.length <= 10) registrarManual(dorsal, "barras")
    }

    // ───────────────────────── Pasadas ─────────────────────────

    fun ahoraCorregido(): Long = System.currentTimeMillis() + (desfaseReloj ?: 0L)

    @Synchronized
    fun registrarLectura(epcCrudo: String, rssi: Int?) {
        val epc = epcCrudo.trim().uppercase()
        if (epc.isEmpty()) return
        val a = ajustes.value
        val t = ahoraCorregido()
        ultimaEpc.value = epc
        val ventana = a.ventanaSeg.coerceAtLeast(1) * 1000L
        val p = abiertas[epc]
        if (p != null && t - p.ultima <= ventana) {
            p.ultima = t
            p.lecturas++
            val r = p.rssi
            if (rssi != null && (r == null || rssi > r)) {
                p.rssi = rssi
                p.mejorTs = t
            }
            p.hora = horaSegunRegla(p)
            db.guardar(p)
        } else {
            if (p != null) cerrar(p)
            val chip = decodificar(epc)
            val i = buscarInscrito(epc, chip)
            val n = Pasada(
                id = UUID.randomUUID().toString(), evento = a.eventoId, epc = epc, chip = chip,
                dorsal = i?.dorsal, nombre = i?.nombre, categoria = i?.categoria,
                punto = a.nombrePunto, modo = a.modo, dispositivo = a.dispositivo,
                primera = t, ultima = t, mejorTs = t, rssi = rssi, lecturas = 1, hora = t,
                cerrada = false, enviado = false, origen = "vivo", sincronizado = desfaseReloj != null,
            )
            abiertas[epc] = n
            db.guardar(n)
            val desconocido = i == null && inscritos.isNotEmpty()
            avisar(
                if (desconocido) TipoAviso.DESCONOCIDO else TipoAviso.CONOCIDO,
                if (i != null) i.dorsal else chip,
                when {
                    i != null -> listOf(i.nombre, i.categoria).filter { it.isNotBlank() }.joinToString(" · ")
                    desconocido -> "Chip no inscrito"
                    else -> ""
                }
            )
        }
        sucio = true
    }

    /** Dorsal ingresado a mano o leído con el escáner de códigos: queda como pasada cerrada al instante. */
    @Synchronized
    fun registrarManual(dorsalCrudo: String, origen: String = "manual"): Boolean {
        val dorsal = dorsalCrudo.trim()
        if (dorsal.isEmpty()) return false
        val a = ajustes.value
        val t = ahoraCorregido()
        val i = porDorsal[dorsal]
        val p = Pasada(
            id = UUID.randomUUID().toString(), evento = a.eventoId,
            epc = i?.epc?.uppercase() ?: "", chip = i?.chip ?: dorsal,
            dorsal = dorsal, nombre = i?.nombre, categoria = i?.categoria,
            punto = a.nombrePunto, modo = a.modo, dispositivo = a.dispositivo,
            primera = t, ultima = t, mejorTs = t, rssi = null, lecturas = 1, hora = t,
            cerrada = true, enviado = false, origen = origen, sincronizado = desfaseReloj != null,
        )
        db.guardar(p)
        avisar(
            TipoAviso.MANUAL, dorsal,
            (if (origen == "barras") "Código de barras" else "Manual") +
                (i?.let { " · " + it.nombre } ?: if (inscritos.isNotEmpty()) " · dorsal no inscrito" else "")
        )
        sucio = true
        return i != null || inscritos.isEmpty()
    }

    private fun horaSegunRegla(p: Pasada): Long {
        val regla = ajustes.value.regla
        val efectiva = if (regla == Regla.AUTO) {
            if (p.modo == Modo.SALIDA) Regla.ULTIMA else Regla.PRIMERA
        } else regla
        return when (efectiva) {
            Regla.ULTIMA -> p.ultima
            Regla.MEJOR -> p.mejorTs
            else -> p.primera
        }
    }

    private fun cerrar(p: Pasada) {
        p.cerrada = true
        p.hora = horaSegunRegla(p)
        db.guardar(p)
        abiertas.remove(p.epc)
    }

    /** Cierra a mano todas las pasadas abiertas (p. ej. al dar la salida). */
    @Synchronized
    fun cerrarTodas() {
        for (p in abiertas.values.toList()) cerrar(p)
        sucio = true
    }

    fun borrarEventoActual() {
        synchronized(this) {
            val ev = ajustes.value.eventoId
            abiertas.values.filter { it.evento == ev }.map { it.epc }.forEach { abiertas.remove(it) }
            db.borrarEvento(ev)
        }
        sucio = true
    }

    private fun cadaSegundo() {
        tick++
        val ventana = ajustes.value.ventanaSeg.coerceAtLeast(1) * 1000L
        val t = ahoraCorregido()
        synchronized(this) {
            val vencidas = abiertas.values.filter { t - it.ultima > ventana }
            if (vencidas.isNotEmpty()) {
                vencidas.forEach { cerrar(it) }
                sucio = true
            }
        }
        val c = conexion.value
        if (c.estado == Estado.CONECTADO && tick % 60 == 0L) driver?.pedirBateria()
        if (quiereConexion && c.estado == Estado.RECONECTANDO &&
            System.currentTimeMillis() - ultimoIntento > 5000
        ) {
            driver?.desconectar()
            abrir()
        }
        if (tick % 2 == 0L) actualizarNotificacion()
    }

    fun marcarSucio() {
        sucio = true
    }

    private fun refrescar() {
        sucio = false
        val ev = ajustes.value.eventoId
        recientes.value = db.recientes(ev, 300)
        val s = db.estadisticas(ev)
        stats.value = s.copy(pendientes = db.pendientesTotales())
        val leidos = db.dorsalesLeidos(ev)
        val l = inscritos
        control.value = Control(
            total = l.size,
            pasaron = l.count { it.dorsal in leidos },
            faltan = l.filter { it.dorsal !in leidos }.sortedWith(compareBy({ it.dorsal.length }, { it.dorsal })),
            desconocidos = if (l.isEmpty()) 0 else db.chipsDesconocidos(ev),
        )
    }

    // ───────────────────────── Avisos ─────────────────────────

    private fun avisar(tipo: TipoAviso, titulo: String, detalle: String) {
        aviso.value = Aviso(tipo, System.currentTimeMillis(), titulo, detalle)
        val a = ajustes.value
        if (a.sonido) pitar(tipo)
        if (a.vibrar) vibrar(tipo)
    }

    private fun pitar(tipo: TipoAviso) {
        try {
            if (tono == null) tono = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            when (tipo) {
                TipoAviso.DESCONOCIDO -> tono?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
                TipoAviso.MANUAL -> tono?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                TipoAviso.CONOCIDO -> tono?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
            }
        } catch (_: RuntimeException) { }
    }

    private fun vibrar(tipo: TipoAviso) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            val efecto = if (tipo == TipoAviso.DESCONOCIDO) {
                VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 120), -1)
            } else {
                VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(efecto)
        } catch (_: Exception) { }
    }

    // ───────────────────────── Utilidades ─────────────────────────

    fun log(s: String) {
        val l = consola.value
        consola.value = (if (l.size > 300) l.takeLast(250) else l) + s
    }

    private fun crearCanal() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Lectura en segundo plano", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parar = PendingIntent.getService(
            app, 1, Intent(app, LectorService::class.java).setAction(LectorService.ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(app, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("NexCrono Pistola · " + ajustes.value.nombrePunto)
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Desconectar", parar).build())
            .build()
    }

    private fun actualizarNotificacion() {
        if (!quiereConexion) return
        val c = conexion.value
        val s = stats.value
        val texto = buildString {
            append(if (c.estado == Estado.CONECTADO) c.nombre else c.mensaje)
            bateria.value?.let { append(" · ").append(it).append("%") }
            append(" · ").append(s.pasadas).append(" pasadas")
            if (s.pendientes > 0) append(" · ").append(s.pendientes).append(" por enviar")
        }
        if (texto == ultimoTextoNotif) return
        ultimoTextoNotif = texto
        try {
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notificacion(texto))
        } catch (_: SecurityException) { }
    }
}
