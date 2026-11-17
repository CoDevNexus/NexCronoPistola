package com.codevnexus.pistola.driver

import android.bluetooth.BluetoothDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lectores TSL (1128, 1153, 1166, 2128) — protocolo «TSL ASCII Protocol 2.5 Rev A».
 *
 *  .iv  inventario     → EP: <epc>, RI: <rssi>, DT: <fecha>  … OK: / ER: 005 (ningún tag)
 *       Los parámetros se fijan una vez con  .iv <param> -n  y quedan para los siguientes .iv,
 *       incluidos los que lanza el gatillo.
 *  .sa  acción del gatillo: -s inv (1 pulsación = inventario), -rs ms (se repite mientras se mantiene)
 *  .bl  batería        → BP: 85%, CH: Off/Charging/Complete/Error
 *  .vr  versión        → MF, US, UF, UB, PV, RS, RF, RB, IA, AS, BA, BV
 *  .da / .tm           → leer o fijar fecha (-s yymmdd) y hora (-s hhmmss) del reloj de la pistola
 *  .rl  registro (LOG.TXT de la micro SD) → LB: … LE:  ;  .rl -d yes  lo borra
 *  .ar  AUTO.TXT de la SD: comandos que se ejecutan cada vez que la pistola despierta
 *       (al dormir, la pistola vuelve a sus valores por defecto — nota «Using the Micro SD Log and Autorun Files»)
 *  .ws  escribir un solo chip (nota «Modifying the EPC of a UHF Transponder»)
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
    private var comandoActual: String? = null
    private var epcPendiente: String? = null
    private var vigilante: ScheduledFuture<*>? = null

    // reloj de la pistola
    private var fechaPistola: String? = null
    private var horaCorregida: () -> Long = { System.currentTimeMillis() }

    @Volatile private var potencia: Int? = null

    // grabado de chips
    @Volatile private var identificando = false
    private var cercanoEpc: String? = null
    private var cercanoRssi: Int? = null
    @Volatile private var grabando = false
    private var grabEsperadas = 0
    private var grabEscritas = -1
    private var grabMensaje = ""

    // AUTO.TXT
    private var enAutorun = false
    private val autorunLineas = ArrayList<String>()
    private var autorunErrores = 0

    // lectura del registro de la SD
    private var logConectado = false
    private var logSinHora = 0
    private var enLog = false
    private var logDt: Long? = null
    private val logLecturas = ArrayList<LecturaMemoria>()
    private var logLineas = 0
    private val fmtDt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun conectar(device: BluetoothDevice) = link.abrir(device)

    override fun desconectar() {
        continuo = false
        link.cerrar()
    }

    override fun configurar(c: ConfigPistola) {
        horaCorregida = c.horaCorregida
        // Cada parámetro por separado: si un firmware no soporta alguno, los demás igual se aplican.
        enviar(".iv -r on -n")                                  // incluir RSSI
        enviar(".iv -dt on -n")                                 // fecha/hora en cada inventario (queda en la SD)
        enviar(".iv -qs s0 -n")                                 // sesión 0: el chip responde en cada ronda (mejor «última lectura»)
        enviar(".iv -al " + (if (c.pitidoPistola) "on" else "off") + " -n")
        fijarPotencia(c.potencia)
        if (c.gatilloContinuo) enviar(".sa -s inv -rs 50")      // mantener el gatillo = lectura continua
        enviar(".da")                                           // leer reloj de la pistola (para comparar)
        enviar(".tm")
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
        potencia = dbm
        enviar(".iv -o " + (dbm ?: 29).coerceIn(10, 29) + " -n")
    }

    override fun grabarAutorun(c: ConfigPistola): Boolean {
        autorunErrores = 0
        enviar(".ar -d yes")
        val lineas = mutableListOf(
            ".iv -n -r on",
            ".iv -n -dt on",
            ".iv -n -qs s0",
            ".iv -n -al " + (if (c.pitidoPistola) "on" else "off"),
            ".iv -n -o " + (c.potencia ?: 29).coerceIn(10, 29),
        )
        if (c.gatilloContinuo) lineas += ".sa -s inv -rs 50"
        for (cmd in lineas) enviar(".ar -wa \"$cmd\"")
        enviar(".ar -ra")
        return true
    }

    override fun leerChipCercano(): Boolean {
        if (identificando || grabando) return false
        identificando = true
        cercanoEpc = null
        cercanoRssi = null
        continuo = false
        enviar(".iv -fs on -o 18")          // solo el chip más fuerte, potencia reducida
        return true
    }

    override fun grabarEpc(actual: String, nuevo: String): Boolean {
        val a = actual.uppercase()
        val n = nuevo.uppercase()
        if (grabando || identificando || a.length != n.length || a.length % 4 != 0 || a.length > 64) return false
        if (!a.all { it in HEX } || !n.all { it in HEX }) return false
        grabando = true
        grabEsperadas = n.length / 4
        grabEscritas = -1
        grabMensaje = ""
        continuo = false
        val palabras = "%02X".format(grabEsperadas)
        val bits = "%02X".format(a.length * 4)
        enviar(".ws -x -da $n -db epc -do 0002 -dl $palabras -sd $a -sb epc -so 0020 -sl $bits -o 20")
        return true
    }

    override fun enviarComando(cmd: String) = enviar(cmd.trim())

    override fun leerMemoria(): Boolean {
        enviar(".rl")
        return true
    }

    override fun borrarMemoria(): Boolean {
        enviar(".rl -d yes")
        return true
    }

    // ─────────────────────────────────────────────

    private fun enviar(cmd: String) {
        l.onLinea(cmd, true)
        link.escribir(cmd + "\r\n")
    }

    @Synchronized
    private fun inventario() {
        if (inventarioPropio) return
        inventarioPropio = true
        enviar(".iv")
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
        // ── Contenido de AUTO.TXT ──
        if (enAutorun) {
            if (linea.startsWith("AE:")) enAutorun = false
            else autorunLineas.add(linea)
            return
        }
        // ── Dentro del volcado del registro de la SD: nada se trata como lectura en vivo ──
        if (enLog) {
            logLineas++
            when {
                linea.startsWith("LE:") -> {
                    enLog = false
                    val n = logLecturas.size
                    var msg = "Memoria leída: $logLineas líneas, $n lecturas sin conexión con hora."
                    if (logSinHora > 0) {
                        msg += "\n$logSinHora lecturas sin hora no se pudieron usar: graba la configuración " +
                            "en la pistola (AUTO.TXT) para que las próximas queden con hora."
                    }
                    l.onMemoria(ArrayList(logLecturas), msg)
                    logLecturas.clear()
                }
                // Cada comando nuevo invalida la hora anterior: la hora solo vale si viene DENTRO del inventario
                linea.startsWith("CS:") -> logDt = null
                linea.startsWith("DT:") -> logDt = parseDt(linea.substring(3).trim())
                linea.startsWith("EV:") -> {
                    val ev = linea.substring(3).trim().lowercase()
                    when {
                        ev.startsWith("connected") -> logConectado = true
                        ev.startsWith("disconnected") || ev.startsWith("wake") || ev.startsWith("sleep") -> logConectado = false
                    }
                    logDt = null
                }
                linea.startsWith("EP:") -> {
                    val epc = linea.substring(3).trim().uppercase()
                    val t = logDt
                    // Lo leído con el teléfono conectado ya se registró en vivo: solo se importa lo leído sin conexión
                    if (epc.isNotEmpty() && !logConectado) {
                        if (t != null) logLecturas.add(LecturaMemoria(epc, t)) else logSinHora++
                    }
                }
            }
            if (logLineas % 500 == 0) l.onLinea("… $logLineas líneas del registro", false)
            return
        }

        l.onLinea(linea, false)
        if (linea.length < 3 || linea[2] != ':') return
        val clave = linea.substring(0, 2)
        val valor = linea.substring(3).trim()
        when (clave) {
            "CS" -> {
                emitirPendiente(null)
                comandoActual = valor
            }
            "EP" -> {
                emitirPendiente(null)
                when {
                    identificando -> if (cercanoEpc == null) cercanoEpc = valor.uppercase()
                    grabando -> { /* EPC anterior del chip que se está grabando */ }
                    valor.isNotEmpty() -> epcPendiente = valor.uppercase()
                }
            }
            "RI" -> if (identificando) { if (cercanoRssi == null) cercanoRssi = valor.toIntOrNull() } else emitirPendiente(valor.toIntOrNull())
            "WW" -> if (grabando) grabEscritas = valor.toIntOrNull() ?: -1
            "EA" -> if (grabando) grabMensaje = "Error de acceso al chip ${ERRORES_CHIP_A[valor.take(3)]?.let { "($it)" } ?: valor}"
            "EB" -> if (grabando) grabMensaje = "El chip respondió error ${ERRORES_CHIP_B[valor.take(3)]?.let { "($it)" } ?: valor}"
            "AB" -> if ((comandoActual ?: "").startsWith(".ar -ra")) {
                enAutorun = true
                autorunLineas.clear()
            }
            "DT", "PC", "IX", "CR" -> { /* datos extra del inventario */ }
            "BC" -> {
                emitirPendiente(null)
                if (valor.isNotEmpty()) l.onCodigoBarras(valor)
            }
            "BP" -> {
                emitirPendiente(null)
                Regex("\\d{1,3}").find(valor)?.value?.toIntOrNull()?.let { l.onBateria(it) }
            }
            "CH" -> l.onInfo("Carga", traducirCarga(valor))
            "DA" -> if (comandoActual == ".da") fechaPistola = valor
            "TM" -> if (comandoActual == ".tm") compararReloj(valor)
            "LB" -> {
                if ((comandoActual ?: "").startsWith(".rl")) {
                    enLog = true
                    logDt = null
                    logLineas = 0
                    logSinHora = 0
                    logConectado = false
                    logLecturas.clear()
                }
            }
            "LL" -> l.onInfo("Tamaño del registro (SD)", "$valor bytes")
            "OK" -> {
                emitirPendiente(null)
                terminado(null)
            }
            "ER" -> {
                emitirPendiente(null)
                terminado(valor)
            }
            "ME" -> {
                if (grabando && grabMensaje.isEmpty()) grabMensaje = valor
                l.onLinea("Mensaje de la pistola: $valor", false)
            }
            "SW" -> { /* evento del gatillo */ }
            else -> {
                emitirPendiente(null)
                val cmd = comandoActual ?: ""
                if (cmd.startsWith(".vr")) {
                    l.onInfo(ETIQUETAS[clave]?.let { "$it ($clave)" } ?: clave, valor)
                }
            }
        }
    }

    private fun parseDt(s: String): Long? = try {
        synchronized(fmtDt) { fmtDt.parse(s.take(19))?.time }
    } catch (e: Exception) {
        null
    }

    private fun traducirCarga(v: String) = when (v.lowercase()) {
        "off" -> "No está cargando"
        "charging" -> "Cargando"
        "complete" -> "Carga completa"
        "error" -> "Error de carga"
        else -> v
    }

    /** Compara el reloj de la pistola con la hora corregida y lo ajusta (precisión de 1 s). */
    private fun compararReloj(hora: String) {
        val fecha = fechaPistola ?: return
        fechaPistola = null
        val pistola = parseDt(fecha + "T" + hora)
        val ahora = horaCorregida()
        if (pistola != null) {
            val dif = (pistola - ahora) / 1000
            l.onInfo("Reloj de la pistola", if (kotlin.math.abs(dif) <= 1) "En hora" else "Tenía ${if (dif > 0) "+" else ""}$dif s · ajustado")
        }
        // Ajustar al segundo siguiente para no quedar atrasado
        val objetivo = ahora + 1000 - (ahora % 1000)
        sched.schedule({
            val d = Date(objetivo)
            enviar(".da -s " + SimpleDateFormat("yyMMdd", Locale.US).format(d))
            enviar(".tm -s " + SimpleDateFormat("HHmmss", Locale.US).format(d))
        }, objetivo - ahora, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun terminado(error: String?) {
        val cmd = comandoActual
        comandoActual = null
        if (identificando && cmd != null && cmd.startsWith(".iv -fs on")) {
            identificando = false
            enviar(".iv -fs off -o " + (potencia ?: 29).coerceIn(10, 29) + " -n")   // volver a lo normal
            l.onChipCercano(if (error == null) cercanoEpc else null, cercanoRssi)
            return
        }
        if (grabando && cmd != null && cmd.startsWith(".ws")) {
            grabando = false
            if (error == null && grabEscritas == grabEsperadas) {
                l.onGrabacion(true, "Chip grabado ($grabEscritas palabras)")
            } else {
                val motivo = when {
                    error?.startsWith("005") == true -> "No se encontró el chip. Acércalo a la antena y vuelve a leerlo."
                    grabMensaje.isNotEmpty() -> grabMensaje
                    error != null -> "Error $error" + (ERRORES[error.take(3)]?.let { " ($it)" } ?: "")
                    else -> "Escritura incompleta ($grabEscritas de $grabEsperadas palabras)"
                }
                l.onGrabacion(false, motivo)
            }
            return
        }
        if (cmd != null && cmd.startsWith(".ar")) {
            when {
                cmd.startsWith(".ar -wa") && error != null -> autorunErrores++
                cmd.startsWith(".ar -ra") -> l.onMemoria(
                    emptyList(),
                    if (error != null || autorunErrores > 0) "No se pudo grabar AUTO.TXT (error ${error ?: "al escribir"}). ¿Tiene tarjeta micro SD?"
                    else "Configuración grabada en la pistola (AUTO.TXT):\n" + autorunLineas.joinToString("\n")
                )
            }
            return
        }
        if (error != null && cmd != null && !cmd.startsWith(".iv")) {
            when {
                cmd.startsWith(".rl -d") ->
                    l.onMemoria(emptyList(), "No se pudo borrar la memoria (error $error). ¿Tiene tarjeta micro SD?")
                cmd.startsWith(".rl") -> {
                    enLog = false
                    l.onMemoria(emptyList(), "La pistola no pudo leer la memoria (error $error). ¿Tiene tarjeta micro SD?")
                }
                cmd.startsWith(".sa") || cmd.startsWith(".da") || cmd.startsWith(".tm") ->
                    l.onLinea("! La pistola no aceptó «$cmd» (error $error)", false)
            }
        }
        if (cmd != null && cmd.startsWith(".rl -d")) {
            if (error == null) l.onMemoria(emptyList(), "Memoria de la pistola borrada")
        }
        val eraInventario = cmd == null || (cmd.startsWith(".iv") && !cmd.contains(" -n"))
        if (!eraInventario || !inventarioPropio) return
        inventarioPropio = false
        vigilante?.cancel(false)

        if (error != null && !error.startsWith("005")) {   // 005 = ningún chip a la vista (normal)
            continuo = false
            l.onError("La pistola respondió error $error" + (ERRORES[error.take(3)]?.let { " ($it)" } ?: ""))
            return
        }
        if (continuo) sched.schedule({ if (continuo) inventario() }, 20, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val ETIQUETAS = mapOf(
            "MF" to "Fabricante",
            "US" to "Nº de serie",
            "UF" to "Firmware",
            "UB" to "Bootloader",
            "PV" to "Versión de protocolo",
            "RS" to "Serie de radio",
            "RF" to "Firmware de radio",
            "RB" to "Bootloader de radio",
            "IA" to "API UHF",
            "AS" to "Serie de antena",
            "BA" to "Dirección Bluetooth",
            "BV" to "Versión Bluetooth",
            "HS" to "Serie de empuñadura",
            "HF" to "Firmware de empuñadura",
            "HB" to "Bootloader de empuñadura",
        )
        private const val HEX = "0123456789ABCDEF"
        private val ERRORES_CHIP_A = mapOf(
            "003" to "el chip no respondió", "004" to "contraseña inválida", "006" to "se perdió el chip",
            "009" to "sin más reintentos", "010" to "acceso denegado", "255" to "operación fallida",
        )
        private val ERRORES_CHIP_B = mapOf(
            "003" to "memoria inexistente", "004" to "memoria bloqueada", "011" to "el chip no tiene suficiente energía; acércalo",
        )
        private val ERRORES = mapOf(
            "001" to "error de sintaxis",
            "002" to "parámetro no soportado",
            "003" to "acción no habilitada",
            "004" to "no soportado por el hardware",
            "007" to "configuración inválida",
            "008" to "error de antena/radio",
            "009" to "batería demasiado baja",
            "014" to "gatillo en uso",
            "015" to "comando abortado",
            "017" to "error Bluetooth",
        )
    }
}
