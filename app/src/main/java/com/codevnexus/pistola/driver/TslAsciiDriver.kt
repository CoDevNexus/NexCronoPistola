package com.codevnexus.pistola.driver

import android.bluetooth.BluetoothDevice
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lectores TSL (1128, 1153, 1166, 2128) con el protocolo ASCII 2.
 *  .iv  inventario  → "EP: <epc>", "RI: <rssi>" … "OK:" / "ER: nnn"   (ER: 005 = ningún tag)
 *  .bl  batería     → "BP: 85%"
 *  .vr  versión     → líneas "XX: valor" (fabricante, serie, firmware…)
 * Con el gatillo, el lector ejecuta su acción configurada (por defecto .iv) y manda las mismas líneas.
 */
class TslAsciiDriver(private val l: DriverListener) : RfidDriver {

    override val soportaComandos = true

    private val link = SppLink(::alRecibir, { l.onConectado() }, { m, u ->
        continuo = false
        l.onDesconectado(m, u)
    })
    private val sched = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var continuo = false
    @Volatile private var inventarioPropio = false
    @Volatile private var usarRssi = true
    @Volatile private var potencia: Int? = null
    private var comandoActual: String? = null
    private var epcPendiente: String? = null
    private var vigilante: ScheduledFuture<*>? = null

    override fun conectar(device: BluetoothDevice) = link.abrir(device)

    override fun desconectar() {
        continuo = false
        link.cerrar()
    }

    override fun leerUnaVez() = inventario()

    override fun iniciarContinuo() {
        continuo = true
        inventario()
    }

    override fun detenerContinuo() {
        continuo = false
    }

    override fun pedirInfo() = enviar(".vr")

    override fun pedirBateria() = enviar(".bl")

    override fun fijarPotencia(dbm: Int?) {
        potencia = dbm?.coerceIn(10, 29)
    }

    override fun enviarComando(cmd: String) = enviar(cmd.trim())

    override fun leerMemoria(): Boolean = false   // pendiente: comandos de la nota de aplicación "Micro SD Log"

    // ─────────────────────────────────────────────

    private fun enviar(cmd: String) {
        l.onLinea(cmd, true)
        link.escribir(cmd + "\r\n")
    }

    @Synchronized
    private fun inventario() {
        if (inventarioPropio) return
        val sb = StringBuilder(".iv")
        if (usarRssi) sb.append(" -r on")
        potencia?.let { sb.append(" -o ").append(it) }
        inventarioPropio = true
        enviar(sb.toString())
        vigilante?.cancel(false)
        vigilante = sched.schedule({
            synchronized(this) {
                if (inventarioPropio) {
                    inventarioPropio = false
                    if (continuo) inventario()
                }
            }
        }, 5, TimeUnit.SECONDS)
    }

    private fun emitirPendiente(rssi: Int?) {
        val e = epcPendiente ?: return
        epcPendiente = null
        l.onTag(e, rssi)
    }

    private fun alRecibir(linea: String) {
        l.onLinea(linea, false)
        if (linea.length < 3 || linea[2] != ':') {
            return
        }
        val clave = linea.substring(0, 2)
        val valor = linea.substring(3).trim()
        when (clave) {
            "CS" -> {
                emitirPendiente(null)
                comandoActual = valor
            }
            "EP" -> {
                emitirPendiente(null)
                if (valor.isNotEmpty()) epcPendiente = valor.uppercase()
            }
            "RI" -> emitirPendiente(valor.toIntOrNull())
            "BC" -> {
                emitirPendiente(null)
                if (valor.isNotEmpty()) l.onCodigoBarras(valor)
            }
            "BP" -> {
                emitirPendiente(null)
                Regex("\\d{1,3}").find(valor)?.value?.toIntOrNull()?.let { l.onBateria(it) }
            }
            "OK" -> {
                emitirPendiente(null)
                terminado(null)
            }
            "ER" -> {
                emitirPendiente(null)
                terminado(valor)
            }
            "SW" -> { /* evento del gatillo */ }
            else -> {
                emitirPendiente(null)
                val cmd = comandoActual ?: ""
                if (cmd.startsWith(".vr") || cmd.startsWith(".bl")) {
                    l.onInfo(ETIQUETAS[clave]?.let { "$it ($clave)" } ?: clave, valor)
                }
            }
        }
    }

    @Synchronized
    private fun terminado(error: String?) {
        val cmd = comandoActual
        comandoActual = null
        val eraInventario = cmd == null || cmd.startsWith(".iv")
        if (!eraInventario || !inventarioPropio) return
        inventarioPropio = false
        vigilante?.cancel(false)

        if (error != null && !error.startsWith("005")) {
            if (usarRssi) {           // quizá este firmware no acepta "-r on": reintentar sin él
                usarRssi = false
                inventario()
                return
            }
            continuo = false
            l.onError("La pistola respondió error $error")
            return
        }
        if (continuo) sched.schedule({ if (continuo) inventario() }, 20, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val ETIQUETAS = mapOf(
            "MF" to "Fabricante",
            "US" to "Nº de serie",
            "PV" to "Versión de protocolo",
            "AS" to "Serie de antena",
            "FV" to "Firmware",
            "RS" to "Serie de radio",
            "RF" to "Firmware de radio",
            "BV" to "Bootloader",
            "BA" to "Dirección Bluetooth",
        )
    }
}
