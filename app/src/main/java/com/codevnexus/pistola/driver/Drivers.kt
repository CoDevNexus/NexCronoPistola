package com.codevnexus.pistola.driver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/** Lo que cada driver le avisa al motor de la app. Se llama desde hilos de fondo. */
interface DriverListener {
    fun onConectado()
    fun onDesconectado(motivo: String?, porUsuario: Boolean)
    fun onTag(epc: String, rssi: Int?)
    /** Código leído con el escáner 2D (dorsal impreso o QR de vinculación). */
    fun onCodigoBarras(codigo: String)
    fun onBateria(porcentaje: Int)
    fun onInfo(clave: String, valor: String)
    fun onLinea(linea: String, saliente: Boolean)
    fun onError(mensaje: String)
}

/** Contrato común para cualquier marca de pistola. Para agregar una marca nueva se implementa esta interfaz. */
interface RfidDriver {
    /** true si la app puede ordenar lecturas, pedir batería, etc. false si la pistola solo envía datos. */
    val soportaComandos: Boolean
    fun conectar(device: BluetoothDevice)
    fun desconectar()
    fun leerUnaVez()
    fun iniciarContinuo()
    fun detenerContinuo()
    fun pedirInfo()
    fun pedirBateria()
    fun fijarPotencia(dbm: Int?)
    fun enviarComando(cmd: String)
    /** Devuelve false si este driver todavía no sabe descargar la memoria/SD de la pistola. */
    fun leerMemoria(): Boolean
}

data class DriverInfo(val id: String, val nombre: String, val detalle: String, val disponible: Boolean)

object Drivers {
    val lista = listOf(
        DriverInfo("tsl", "TSL · protocolo ASCII 2", "1128, 1153, 1166, 2128 por Bluetooth", true),
        DriverInfo("spp", "Genérica Bluetooth (texto)", "Pistolas que envían el EPC como texto por puerto serie (SPP)", true),
        DriverInfo("zebra", "Zebra RFD40 / RFD8500", "Requiere integrar el SDK de Zebra", false),
        DriverInfo("chainway", "Chainway R5 / R6", "Requiere integrar el SDK de Chainway", false),
    )

    fun crear(id: String, l: DriverListener): RfidDriver = when (id) {
        "spp" -> GenericSppDriver(l)
        else -> TslAsciiDriver(l)
    }

    fun nombre(id: String) = lista.firstOrNull { it.id == id }?.nombre ?: id
}

/** Conexión Bluetooth clásica (SPP) que entrega el texto línea por línea. */
class SppLink(
    private val onLinea: (String) -> Unit,
    private val onAbierto: () -> Unit,
    private val onCerrado: (motivo: String?, porUsuario: Boolean) -> Unit,
) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var cerrandoPorUsuario = false
    private val escritor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    fun abrir(device: BluetoothDevice) {
        cerrandoPorUsuario = false
        Thread({
            var motivo: String? = null
            var s: BluetoothSocket? = null
            try {
                val seguro = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s = seguro
                socket = seguro
                val conectado: BluetoothSocket = try {
                    seguro.connect()
                    seguro
                } catch (e: IOException) {
                    try { seguro.close() } catch (_: IOException) { }
                    if (cerrandoPorUsuario) throw e
                    val inseguro = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    s = inseguro
                    socket = inseguro
                    inseguro.connect()
                    inseguro
                }
                socket = conectado
                out = conectado.outputStream
                onAbierto()
                val br = BufferedReader(InputStreamReader(conectado.inputStream, StandardCharsets.ISO_8859_1))
                while (true) {
                    val linea = br.readLine() ?: break
                    val t = linea.trim()
                    if (t.isNotEmpty()) onLinea(t)
                }
            } catch (e: IOException) {
                motivo = e.message
            } catch (e: SecurityException) {
                motivo = "Falta el permiso de Bluetooth"
            } finally {
                try { s?.close() } catch (_: IOException) { }
                socket = null
                out = null
                onCerrado(motivo, cerrandoPorUsuario)
            }
        }, "spp-link").start()
    }

    fun escribir(texto: String) {
        val o = out ?: return
        escritor.execute {
            try {
                o.write(texto.toByteArray(StandardCharsets.ISO_8859_1))
                o.flush()
            } catch (_: IOException) { }
        }
    }

    fun cerrar() {
        cerrandoPorUsuario = true
        try { socket?.close() } catch (_: IOException) { }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Pistolas que, en modo SPP, mandan cada lectura como una línea de texto.
 * Toma de cada línea la secuencia hexadecimal más larga (8 a 64 caracteres) como EPC.
 */
class GenericSppDriver(private val l: DriverListener) : RfidDriver {
    override val soportaComandos = false
    private val link = SppLink(::alRecibir, { l.onConectado() }, { m, u -> l.onDesconectado(m, u) })
    private val hex = Regex("[0-9A-Fa-f]{8,64}")

    private fun alRecibir(linea: String) {
        l.onLinea(linea, false)
        val epc = hex.findAll(linea).maxByOrNull { it.value.length }?.value ?: return
        val rssi = Regex("(-\\d{2,3})").find(linea.replace(epc, ""))?.value?.toIntOrNull()
        l.onTag(epc.uppercase(), rssi)
    }

    override fun conectar(device: BluetoothDevice) = link.abrir(device)
    override fun desconectar() = link.cerrar()
    override fun leerUnaVez() {}
    override fun iniciarContinuo() {}
    override fun detenerContinuo() {}
    override fun pedirInfo() {}
    override fun pedirBateria() {}
    override fun fijarPotencia(dbm: Int?) {}
    override fun enviarComando(cmd: String) {
        l.onLinea(cmd, true)
        link.escribir(cmd + "\r\n")
    }
    override fun leerMemoria() = false
}
