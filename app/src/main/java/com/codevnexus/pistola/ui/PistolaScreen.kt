package com.codevnexus.pistola.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Estado
import com.codevnexus.pistola.driver.Drivers

fun BluetoothDevice.nombreSeguro(): String = try {
    @SuppressLint("MissingPermission")
    val n = name
    n ?: address
} catch (e: SecurityException) {
    address
}

@SuppressLint("MissingPermission")
@Composable
fun PistolaScreen() {
    val ctx = LocalContext.current
    val adapter: BluetoothAdapter? = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val conexion by Engine.conexion.collectAsState()
    val bateria by Engine.bateria.collectAsState()
    val info by Engine.info.collectAsState()
    val consola by Engine.consola.collectAsState()
    val ajustes by Engine.ajustes.collectAsState()

    var refresco by remember { mutableIntStateOf(0) }
    var buscando by remember { mutableStateOf(false) }
    val encontrados = remember { mutableStateListOf<BluetoothDevice>() }
    var aviso by remember { mutableStateOf<String?>(null) }
    var comando by remember { mutableStateOf("") }
    var potencia by remember { mutableFloatStateOf((ajustes.potencia ?: 29).toFloat()) }
    var comandosIni by remember(ajustes.comandosConectar) { mutableStateOf(ajustes.comandosConectar) }
    val memoria by Engine.memoria.collectAsState()
    var confirmarBorrarMemoria by remember { mutableStateOf(false) }
    val grabacion by Engine.grabacion.collectAsState()
    var numeroChip by remember { mutableStateOf("1") }
    var prefijoChip by remember { mutableStateOf("") }
    var autoIncrementar by remember { mutableStateOf(true) }
    var ultimoVerificado by remember { mutableStateOf<String?>(null) }
    // al verificar un chip, avanzar al número siguiente
    androidx.compose.runtime.LaunchedEffect(grabacion.verificado) {
        val v = grabacion.verificado
        if (v != null && v != ultimoVerificado) {
            ultimoVerificado = v
            if (autoIncrementar) numeroChip = ((numeroChip.toLongOrNull() ?: 0L) + 1).toString()
        }
    }

    DisposableEffect(Unit) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (d != null && encontrados.none { it.address == d.address }) encontrados.add(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> buscando = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refresco++
                }
            }
        }
        val filtro = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receptor, filtro)
        onDispose {
            try { ctx.unregisterReceiver(receptor) } catch (_: Exception) { }
            try { adapter?.cancelDiscovery() } catch (_: SecurityException) { }
        }
    }

    val emparejados: List<BluetoothDevice> = remember(refresco) {
        try {
            adapter?.bondedDevices?.toList()?.sortedByDescending {
                val n = it.nombreSeguro().uppercase()
                n.contains("1128") || n.contains("TSL") || n.contains("RFID") || n.contains("UHF")
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    memoria?.let { texto ->
        val enCurso = texto.endsWith("…")
        AlertDialog(
            onDismissRequest = { if (!enCurso) Engine.memoria.value = null },
            title = { Text("Pistola") },
            text = {
                Column {
                    Text(texto)
                    if (enCurso) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Si la memoria es grande puede tardar varios minutos. No apagues la pistola.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Engine.memoria.value = null }) { Text(if (enCurso) "Ocultar" else "OK") } },
        )
    }
    if (confirmarBorrarMemoria) {
        AlertDialog(
            onDismissRequest = { confirmarBorrarMemoria = false },
            title = { Text("¿Borrar la memoria de la pistola?") },
            text = { Text("Se borra el registro (LOG.TXT) de la micro SD. Hazlo solo después de importar y enviar las lecturas.") },
            confirmButton = {
                TextButton(onClick = { Engine.borrarMemoria(); confirmarBorrarMemoria = false }) { Text("Borrar", color = Rojo) }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrarMemoria = false }) { Text("Cancelar") } },
        )
    }

    aviso?.let { texto ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido") } },
            title = { Text("Aviso") },
            text = { Text(texto) },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ── Pistola conectada ──
        item {
            Tarjeta {
                Titulo("Pistola")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Punto(
                        when (conexion.estado) {
                            Estado.CONECTADO -> Turquesa
                            Estado.DESCONECTADO -> Rojo
                            else -> Amarillo
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (conexion.estado == Estado.DESCONECTADO) "Ninguna conectada" else conexion.nombre,
                            fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        )
                        Text(conexion.mensaje, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (conexion.estado != Estado.DESCONECTADO) {
                        OutlinedButton(onClick = { Engine.desconectar() }) { Text("Desconectar") }
                    }
                }
                if (bateria != null) {
                    Spacer(Modifier.height(12.dp))
                    val b = bateria ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Batería", modifier = Modifier.width(70.dp))
                        LinearProgressIndicator(
                            progress = { b / 100f },
                            modifier = Modifier.weight(1f).height(10.dp),
                            color = colorBateria(b),
                            trackColor = MaterialTheme.colorScheme.background,
                        )
                        Text(" $b%", fontWeight = FontWeight.Bold)
                    }
                }
                if (conexion.estado == Estado.CONECTADO) {
                    Spacer(Modifier.height(10.dp))
                    if (info.isEmpty()) {
                        Text("Sin datos de la pistola todavía.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        info.forEach { (k, v) -> FilaDato(k, v) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Engine.actualizarInfo() }, modifier = Modifier.weight(1f)) {
                            Text("Actualizar info")
                        }
                        OutlinedButton(
                            onClick = {
                                if (!Engine.leerMemoria()) {
                                    aviso = "Esta pistola no permite descargar su memoria desde la app."
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Leer memoria / SD") }
                    }
                    if (conexion.comandos) {
                        TextButton(onClick = { confirmarBorrarMemoria = true }) { Text("Borrar memoria de la pistola", color = Rojo) }
                        Text(
                            "La memoria guarda lo leído con el gatillo cuando la pistola no estaba conectada " +
                                "(requiere micro SD). Al leerla, las pasadas se agregan al evento actual.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Potencia ──
        if (conexion.comandos) {
            item {
                Tarjeta {
                    Titulo("Potencia de lectura")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (ajustes.potencia == null) "La de la pistola" else "${potencia.toInt()} dBm",
                            modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold,
                        )
                        Text("Fijar", fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = ajustes.potencia != null,
                            onCheckedChange = { on ->
                                Engine.guardarAjustes(ajustes.copy(potencia = if (on) potencia.toInt() else null))
                            },
                        )
                    }
                    if (ajustes.potencia != null) {
                        Slider(
                            value = potencia,
                            onValueChange = { potencia = it },
                            valueRange = 10f..29f,
                            steps = 18,
                            onValueChangeFinished = { Engine.guardarAjustes(ajustes.copy(potencia = potencia.toInt())) },
                        )
                        Text(
                            "Menos potencia = menos alcance (útil para no leer chips de corrales vecinos).",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Tipo de pistola ──
        item {
            Tarjeta {
                Titulo("Tipo de pistola")
                Drivers.lista.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = d.disponible) {
                                if (ajustes.driverId != d.id) Engine.guardarAjustes(ajustes.copy(driverId = d.id))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = ajustes.driverId == d.id,
                            onClick = { Engine.guardarAjustes(ajustes.copy(driverId = d.id)) },
                            enabled = d.disponible,
                        )
                        Column {
                            Text(d.nombre, fontWeight = FontWeight.Medium,
                                color = if (d.disponible) MaterialTheme.colorScheme.onSurface else Gris)
                            Text(d.detalle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "El cambio se aplica en la próxima conexión.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Emparejadas ──
        item {
            Tarjeta {
                Titulo("Pistolas emparejadas")
                if (adapter == null) {
                    Text("Este teléfono no tiene Bluetooth.")
                } else if (!adapter.isEnabled) {
                    Text("El Bluetooth está apagado.")
                    TextButton(onClick = {
                        try { ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { }
                    }) { Text("Activar Bluetooth") }
                } else if (emparejados.isEmpty()) {
                    Text("No hay dispositivos emparejados. Búscalos abajo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                emparejados.forEach { d ->
                    val esUltima = d.address == ajustes.ultimaPistola
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .clickable { Engine.conectar(d, d.nombreSeguro()) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.nombreSeguro(), fontWeight = FontWeight.Bold)
                            Text(d.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (esUltima) Etiqueta("ÚLTIMA", Turquesa)
                        Spacer(Modifier.width(8.dp))
                        Text("Conectar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Buscar y emparejar ──
        item {
            Tarjeta {
                Titulo("Emparejar nueva pistola")
                Text(
                    "Enciende la pistola. Si pide PIN, normalmente es 0000.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            try {
                                if (buscando) {
                                    adapter?.cancelDiscovery(); buscando = false
                                } else {
                                    encontrados.clear()
                                    buscando = adapter?.startDiscovery() == true
                                    if (!buscando) aviso = "No se pudo buscar. Revisa que el Bluetooth esté encendido y los permisos concedidos."
                                }
                            } catch (e: SecurityException) {
                                aviso = "Falta el permiso de dispositivos cercanos / Bluetooth."
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (buscando) "Detener búsqueda" else "Buscar pistolas") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Text("Ajustes BT")
                    }
                }
                encontrados.filter { e -> emparejados.none { it.address == e.address } }.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.nombreSeguro(), fontWeight = FontWeight.Medium)
                            Text(d.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            try {
                                adapter?.cancelDiscovery()
                                buscando = false
                                d.createBond()
                            } catch (e: SecurityException) {
                                aviso = "Falta el permiso de Bluetooth."
                            }
                        }) { Text("Emparejar") }
                    }
                }
            }
        }

        // ── Configuración automática de la pistola ──
        if (conexion.comandos) {
            item {
                Tarjeta {
                    Titulo("Configuración de la pistola")
                    Interruptor(
                        "Configurar la pistola al conectar",
                        "RSSI, fecha/hora en cada lectura, potencia y sincronizar su reloj",
                        ajustes.configurarPistola,
                    ) { Engine.guardarAjustes(ajustes.copy(configurarPistola = it)) }
                    if (ajustes.configurarPistola) {
                        Interruptor(
                            "Mantener el gatillo = leer sin parar",
                            "Una pulsación lee; mantenido repite cada 50 ms",
                            ajustes.gatilloContinuo,
                        ) { Engine.guardarAjustes(ajustes.copy(gatilloContinuo = it)) }
                        Interruptor(
                            "Pitido de la pistola en cada lectura",
                            "Apagado: solo pita el teléfono cuando hay un chip nuevo",
                            ajustes.pitidoPistola,
                        ) { Engine.guardarAjustes(ajustes.copy(pitidoPistola = it)) }
                        Text(
                            "Los cambios se aplican al volver a conectar la pistola.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = conexion.estado == Estado.CONECTADO,
                            onClick = { if (!Engine.grabarAutorun()) aviso = "La pistola no permite grabar su configuración." },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("Grabar configuración en la pistola (AUTO.TXT)") }
                        Text(
                            "Necesario para usar la pistola SIN el teléfono: al dormirse vuelve a sus valores de fábrica " +
                                "y sin esto las lecturas guardadas en la SD quedan sin hora. Requiere micro SD. " +
                                "Repítelo si cambias la potencia o el gatillo.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Grabar número en chips ──
        if (conexion.comandos && conexion.estado == Estado.CONECTADO) {
            item {
                Tarjeta {
                    Titulo("Grabar número en chips")
                    Text(
                        "Escribe el número en el EPC del chip. Deja los demás chips lejos (o en una caja metálica) " +
                            "y pon solo el que vas a grabar junto a la antena.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            value = numeroChip, onValueChange = { numeroChip = it.filter(Char::isDigit).take(12) },
                            label = { Text("Número") }, singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.titleLarge,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                        )
                        OutlinedTextField(
                            value = prefijoChip,
                            onValueChange = { prefijoChip = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(8) },
                            label = { Text("Prefijo (opcional)") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Interruptor("Pasar al número siguiente al grabar", "", autoIncrementar) { autoIncrementar = it }
                    val actual = grabacion.epcActual
                    if (actual != null) {
                        Text("EPC actual", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(actual, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        val nuevo = Engine.epcNuevo(actual, numeroChip, prefijoChip)
                        Text("Se grabará", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(nuevo ?: "— el número no cabe —", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = if (nuevo != null) Turquesa else Rojo)
                    }
                    if (grabacion.mensaje.isNotEmpty()) {
                        Text(
                            grabacion.mensaje, modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.Medium,
                            color = when (grabacion.ok) { true -> Turquesa; false -> Rojo; else -> MaterialTheme.colorScheme.onSurface },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(
                            enabled = !grabacion.ocupado,
                            onClick = { if (!Engine.leerChipCercano()) aviso = "La pistola está ocupada; inténtalo de nuevo." },
                            modifier = Modifier.weight(1f),
                        ) { Text("1. Leer chip") }
                        Button(
                            enabled = !grabacion.ocupado && actual != null,
                            onClick = { Engine.grabarChip(numeroChip, prefijoChip)?.let { aviso = it } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Naranja),
                        ) { Text("2. Grabar $numeroChip") }
                    }
                    Text(
                        "El número queda al final del EPC con ceros a la izquierda (ej. …000001234), así la app lo " +
                            "muestra con la opción «Últimos dígitos». Los chips con contraseña o bloqueados no se pueden grabar.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ── Comandos al conectar (gatillo, etc.) ──
        if (conexion.comandos) {
            item {
                Tarjeta {
                    Titulo("Comandos al conectar (avanzado)")
                    Text(
                        "Se envían a la pistola cada vez que se conecta. Sirven para dejarla configurada, " +
                            "por ejemplo el comportamiento del gatillo. Uno por línea; las líneas con # se ignoran.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = comandosIni, onValueChange = { comandosIni = it },
                        placeholder = { Text("# ej.: .st -t 300  (apagado tras 5 min sin conexión)") },
                        minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (comandosIni != ajustes.comandosConectar) {
                        Button(
                            onClick = { Engine.guardarAjustes(ajustes.copy(comandosConectar = comandosIni)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        ) { Text("Guardar") }
                    }
                }
            }
        }

        // ── Consola ──
        if (conexion.estado == Estado.CONECTADO) {
            item {
                Tarjeta {
                    Titulo("Consola de comandos")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = comando, onValueChange = { comando = it },
                            singleLine = true, placeholder = { Text(".vr") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { Engine.enviarComando(comando); comando = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = Naranja),
                        ) { Text("Enviar") }
                    }
                }
            }
        }
        item { Titulo("Registro") }
        items(consola.takeLast(60).reversed()) { linea ->
            Text(
                linea, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = when {
                    linea.startsWith("→") -> Naranja
                    linea.startsWith("!") -> Rojo
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Interruptor(titulo: String, detalle: String, valor: Boolean, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontWeight = FontWeight.Medium)
            if (detalle.isNotEmpty()) Text(detalle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = valor, onCheckedChange = onCambio)
    }
}
