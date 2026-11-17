package com.codevnexus.pistola.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import com.codevnexus.pistola.MainActivity
import com.codevnexus.pistola.R
import com.codevnexus.pistola.driver.DriverListener
import com.codevnexus.pistola.driver.Drivers
import com.codevnexus.pistola.driver.RfidDriver
import com.codevnexus.pistola.service.LectorService
import kotlinx.coroutines.flow.MutableStateFlow
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
    val servidor = MutableStateFlow(Servidor())
    val consola = MutableStateFlow<List<String>>(emptyList())
    val ultimaEpc = MutableStateFlow<String?>(null)
    val tablaTam = MutableStateFlow(0)

    @Volatile var desfaseReloj: Long? = null
    @Volatile private var tabla: Map<String, String> = emptyMap()

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
        ajustes.value = Ajustes.cargar(app)
        tabla = db.cargarMapa()
        tablaTam.value = tabla.size
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

    // ───────────────────────── Ajustes ─────────────────────────

    fun guardarAjustes(a: Ajustes) {
        val antes = ajustes.value
        ajustes.value = a
        Ajustes.guardar(app, a)
        if (a.chipModo != antes.chipModo || a.chipDigitos != antes.chipDigitos) recalcularChips()
        if (a.potencia != antes.potencia) driver?.fijarPotencia(a.potencia)
        if (a.servidor != antes.servidor) desfaseReloj = null
        sucio = true
    }

    fun importarTabla(m: Map<String, String>) {
        db.guardarMapa(m)
        tabla = m
        tablaTam.value = m.size
        recalcularChips()
    }

    fun tablaParaVista(): Map<String, String> = tabla

    fun decodificar(epc: String) = ChipDecoder.decodificar(epc, ajustes.value, tabla)

    private fun recalcularChips() {
        synchronized(this) {
            db.recalcularChips { decodificar(it) }
            for (p in abiertas.values) p.chip = decodificar(p.epc)
        }
        sucio = true
    }

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
            val bt = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return false
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
        d.fijarPotencia(ajustes.value.potencia)
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

    fun leerUnaVez() = driver?.leerUnaVez()

    fun actualizarInfo() {
        info.value = emptyList()
        driver?.pedirInfo()
        driver?.pedirBateria()
    }

    fun leerMemoria(): Boolean = driver?.leerMemoria() ?: false

    fun enviarComando(cmd: String) {
        if (cmd.isNotBlank()) driver?.enviarComando(cmd)
    }

    // ───────────────────────── DriverListener ─────────────────────────

    /** Solo deja pasar eventos del driver vigente (evita que una conexión vieja pise a la nueva). */
    private class Oyente : DriverListener {
        @Volatile var dueno: RfidDriver? = null
        private fun vigente() = dueno != null && dueno === driver
        override fun onConectado() { if (vigente()) Engine.onConectado() }
        override fun onDesconectado(motivo: String?, porUsuario: Boolean) {
            if (vigente()) Engine.onDesconectado(motivo, porUsuario)
        }
        override fun onTag(epc: String, rssi: Int?) { if (vigente()) Engine.onTag(epc, rssi) }
        override fun onBateria(porcentaje: Int) { if (vigente()) Engine.onBateria(porcentaje) }
        override fun onInfo(clave: String, valor: String) { if (vigente()) Engine.onInfo(clave, valor) }
        override fun onLinea(linea: String, saliente: Boolean) { Engine.onLinea(linea, saliente) }
        override fun onError(mensaje: String) { if (vigente()) Engine.onError(mensaje) }
    }

    private fun onConectado() {
        conexion.value = Conexion(Estado.CONECTADO, pistolaNombre, "Conectada", driver?.soportaComandos ?: true)
        actualizarInfo()
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

    private fun onTag(epc: String, rssi: Int?) = registrarLectura(epc, rssi, "vivo")

    private fun onBateria(porcentaje: Int) {
        bateria.value = porcentaje.coerceIn(0, 100)
    }

    private fun onInfo(clave: String, valor: String) {
        info.value = info.value.filter { it.first != clave } + (clave to valor)
    }

    private fun onLinea(linea: String, saliente: Boolean) = log((if (saliente) "→ " else "← ") + linea)

    private fun onError(mensaje: String) {
        leyendo.value = false
        log("! $mensaje")
        conexion.value = conexion.value.copy(mensaje = mensaje)
    }

    // ───────────────────────── Pasadas ─────────────────────────

    fun ahoraCorregido(): Long = System.currentTimeMillis() + (desfaseReloj ?: 0L)

    @Synchronized
    fun registrarLectura(epcCrudo: String, rssi: Int?, origen: String) {
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
            val n = Pasada(
                id = UUID.randomUUID().toString(), epc = epc, chip = decodificar(epc),
                punto = a.nombrePunto, modo = a.modo, dispositivo = a.dispositivo,
                primera = t, ultima = t, mejorTs = t, rssi = rssi, lecturas = 1, hora = t,
                cerrada = false, enviado = false, origen = origen, sincronizado = desfaseReloj != null,
            )
            abiertas[epc] = n
            db.guardar(n)
            if (a.sonido) pitar()
        }
        sucio = true
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

    /** Cierra a mano todas las pasadas abiertas (p. ej. al terminar la salida). */
    @Synchronized
    fun cerrarTodas() {
        for (p in abiertas.values.toList()) cerrar(p)
        sucio = true
    }

    fun borrarTodo() {
        synchronized(this) {
            abiertas.clear()
            db.borrarTodo()
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
        recientes.value = db.recientes(300)
        stats.value = db.estadisticas()
    }

    // ───────────────────────── Utilidades ─────────────────────────

    fun log(s: String) {
        val l = consola.value
        consola.value = (if (l.size > 300) l.takeLast(250) else l) + s
    }

    private fun pitar() {
        try {
            if (tono == null) tono = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tono?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        } catch (_: RuntimeException) { }
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
