package com.codevnexus.pistola.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.BuildConfig
import com.codevnexus.pistola.core.Actualizador
import com.codevnexus.pistola.core.ChipDecoder
import com.codevnexus.pistola.core.ChipModo
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Exportador
import com.codevnexus.pistola.core.Licencia
import com.codevnexus.pistola.core.Modo
import com.codevnexus.pistola.core.Regla
import com.codevnexus.pistola.core.Uploader
import com.codevnexus.pistola.core.Vinculo
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AjustesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val guardado by Engine.ajustes.collectAsState()
    val ultimaEpc by Engine.ultimaEpc.collectAsState()
    val tablaTam by Engine.tablaTam.collectAsState()
    val inscritosTam by Engine.inscritosTam.collectAsState()
    val servidor by Engine.servidor.collectAsState()
    val licencia by Licencia.estado.collectAsState()
    val codigoTel = remember { Licencia.codigoTelefono(ctx) }
    var verBitacora by remember { mutableStateOf(false) }
    val bitacora by androidx.compose.runtime.produceState(emptyList<com.codevnexus.pistola.core.RegistroBitacora>(), verBitacora, servidor) {
        value = if (verBitacora) withContext(Dispatchers.IO) { Engine.bitacora(60) } else emptyList()
    }

    var a by remember(guardado) { mutableStateOf(guardado) }
    var digitosTxt by remember(guardado) { mutableStateOf(guardado.chipDigitos.toString()) }
    var ventanaTxt by remember(guardado) { mutableStateOf(guardado.ventanaSeg.toString()) }
    var segInicioTxt by remember(guardado) { mutableStateOf(guardado.segInicio.toString()) }
    var segLargoTxt by remember(guardado) { mutableStateOf(guardado.segLargo.toString()) }
    var numeroImpreso by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var confirmarBorrado by remember { mutableStateOf(false) }
    var confirmarDesvincular by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var codigoManual by remember { mutableStateOf("") }

    val cambios = a != guardado ||
        digitosTxt != guardado.chipDigitos.toString() || ventanaTxt != guardado.ventanaSeg.toString() ||
        segInicioTxt != guardado.segInicio.toString() || segLargoTxt != guardado.segLargo.toString()

    fun conTextos(x: com.codevnexus.pistola.core.Ajustes) = x.copy(
        chipDigitos = digitosTxt.toIntOrNull()?.coerceIn(1, 24) ?: x.chipDigitos,
        ventanaSeg = ventanaTxt.toIntOrNull()?.coerceIn(1, 600) ?: x.ventanaSeg,
        segInicio = segInicioTxt.toIntOrNull()?.coerceIn(1, 64) ?: x.segInicio,
        segLargo = segLargoTxt.toIntOrNull()?.coerceIn(1, 15) ?: x.segLargo,
    )

    fun guardar() {
        Engine.guardarAjustes(conTextos(a))
        mensaje = "Ajustes guardados"
    }

    fun enFondo(tarea: () -> String) {
        trabajando = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { tarea() } catch (e: Exception) { "Error: ${e.message}" }
            }
            trabajando = false
            mensaje = r
        }
    }

    fun compartir(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Compartir respaldo"))
    }

    fun exportar(excel: Boolean) {
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { Exportador.exportar(ctx, excel) }
                compartir(uri, if (excel) Exportador.MIME_XLSX else Exportador.MIME_CSV)
            } catch (e: Exception) {
                mensaje = e.message ?: "No se pudo exportar"
            }
        }
    }

    val abrirLicencia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val texto = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.takeIf { it.size < 20_000 }?.toString(Charsets.UTF_8) ?: throw Exception("archivo vacío o demasiado grande")
                    Licencia.describir(Licencia.cargar(ctx, texto))
                } catch (e: Exception) {
                    "No se pudo leer la licencia: ${e.message}"
                }
            }
            mensaje = r
        }
    }

    val escanear = rememberLauncherForActivityResult(ScanContract()) { r ->
        val texto = r.contents ?: return@rememberLauncherForActivityResult
        val v = Vinculo.desde(texto)
        if (v != null) Engine.vinculoPendiente.value = v
        else mensaje = "Ese QR no es de vinculación de NexCrono."
    }

    val abrirTabla = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val m = withContext(Dispatchers.IO) { Exportador.leerTabla(ctx, uri) }
                    if (m.isEmpty()) {
                        mensaje = "No encontré filas EPC,chip en el archivo"
                    } else {
                        withContext(Dispatchers.IO) { Engine.importarTabla(m) }
                        a = a.copy(chipModo = ChipModo.TABLA)
                        mensaje = "Tabla importada: ${m.size} chips. Guarda para usar el modo Tabla."
                    }
                } catch (e: Exception) {
                    mensaje = "No se pudo leer: ${e.message}"
                }
            }
        }
    }

    // ── Diálogos ──
    mensaje?.let {
        AlertDialog(
            onDismissRequest = { mensaje = null },
            confirmButton = { TextButton(onClick = { mensaje = null }) { Text("OK") } },
            text = { Text(it) },
        )
    }
    if (confirmarBorrado) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            title = { Text("¿Borrar las pasadas de este evento?") },
            text = { Text("Se eliminan del teléfono las pasadas de «${guardado.eventoNombre}». Exporta un respaldo antes si lo necesitas.") },
            confirmButton = {
                TextButton(onClick = { Engine.borrarEventoActual(); confirmarBorrado = false }) { Text("Borrar", color = Rojo) }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } },
        )
    }
    if (confirmarDesvincular) {
        AlertDialog(
            onDismissRequest = { confirmarDesvincular = false },
            title = { Text("¿Desvincular de NexCrono?") },
            text = { Text("El teléfono deja de enviar lecturas. Las pasadas guardadas no se borran.") },
            confirmButton = {
                TextButton(onClick = { Uploader.desvincular(); confirmarDesvincular = false }) { Text("Desvincular", color = Rojo) }
            },
            dismissButton = { TextButton(onClick = { confirmarDesvincular = false }) { Text("Cancelar") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Ajustes", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                BotonInfo("Ajustes")
            }
        }

        // ── NexCrono ──
        item {
            Tarjeta {
                Titulo("Conexión con NexCrono")
                if (guardado.vinculado) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Punto(if (servidor.ok) Turquesa else Amarillo)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("Vinculado" + if (guardado.empresa.isNotBlank()) " · ${guardado.empresa}" else "", fontWeight = FontWeight.Bold)
                            Text(guardado.servidor, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(servidor.mensaje, fontSize = 12.sp, color = if (servidor.ok) Turquesa else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Enviar automáticamente", modifier = Modifier.weight(1f))
                        Switch(checked = a.autoEnvio, onCheckedChange = { a = a.copy(autoEnvio = it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(enabled = !trabajando, onClick = { enFondo { Uploader.sincronizarHora() } }, modifier = Modifier.weight(1f)) {
                            Text("Sincronizar hora")
                        }
                        OutlinedButton(enabled = !trabajando, onClick = { enFondo { Uploader.enviarPendientes() } }, modifier = Modifier.weight(1f)) {
                            Text("Enviar ahora")
                        }
                    }
                    TextButton(onClick = { confirmarDesvincular = true }) { Text("Desvincular", color = Rojo) }
                } else {
                    Text(
                        "En NexCrono genera el QR de vinculación del punto y escanéalo. " +
                            "También puedes escanearlo con la cámara del teléfono o con la pistola (si tiene lector 2D).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            escanear.launch(
                                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Escanea el QR de NexCrono").setBeepEnabled(false).setOrientationLocked(false)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                    ) { Text("ESCANEAR QR DE NEXCRONO", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))
                    Text("O a mano:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = a.servidor, onValueChange = { a = a.copy(servidor = it.trim()) },
                        label = { Text("Dirección (se puede dejar para después)") }, singleLine = true,
                        placeholder = { Text("http://192.168.1.10:8080") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = codigoManual, onValueChange = { codigoManual = it.trim().uppercase().take(20) },
                        label = { Text("Código de vinculación") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        enabled = !trabajando && a.servidor.startsWith("http") && codigoManual.isNotBlank(),
                        onClick = {
                            Engine.vinculoPendiente.value = Vinculo(a.servidor.trimEnd('/'), codigoManual, null, null, null, null)
                            codigoManual = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Vincular") }
                }
            }
        }

        // ── Licencia ──
        item {
            Tarjeta {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Titulo("Licencia")
                    Spacer(Modifier.weight(1f))
                    BotonInfo("Licencia")
                }
                val ok = licencia is Licencia.Estado.Valida || licencia is Licencia.Estado.SinControl
                Text(Licencia.describir(licencia), fontWeight = FontWeight.Bold, color = if (ok) Turquesa else Amarillo)
                if (!ok) {
                    Text(
                        "Modo demostración: hasta ${Licencia.DEMO_PASADAS} pasadas por evento.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Código de este teléfono", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(codigoTel, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Código NexCrono", codigoTel))
                        mensaje = "Código copiado"
                    }, modifier = Modifier.weight(1f)) { Text("Copiar") }
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                            Intent.EXTRA_TEXT,
                            "Solicitud de licencia NexCrono Pistola\nCódigo del teléfono: $codigoTel\nEquipo: ${guardado.dispositivo}",
                        )
                        ctx.startActivity(Intent.createChooser(i, "Enviar código"))
                    }, modifier = Modifier.weight(1f)) { Text("Enviar") }
                }
                Button(
                    onClick = { abrirLicencia.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { Text("Cargar licencia (.lic)") }
                if (licencia is Licencia.Estado.Valida) {
                    val d = (licencia as Licencia.Estado.Valida).datos
                    Text("Plan ${d.plan} · emitida ${d.emitida} · ID ${d.id.take(8)}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // ── Evento actual (la gestión está en la pestaña Eventos) ──
        item {
            Tarjeta {
                Titulo("Evento actual")
                Text(guardado.eventoNombre, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    (if (guardado.eventoLocal) "Evento local" else "Evento de NexCrono") + " · $inscritosTam participantes",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { Navegacion.pestana = 2 }) { Text("Ver y gestionar eventos →") }
            }
        }

        // ── Punto de captura ──
        item {
            Tarjeta {
                Titulo("Punto de captura")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = a.modo == Modo.SALIDA, onClick = { a = a.copy(modo = Modo.SALIDA) },
                        label = { Text("Salida controlada") },
                    )
                    FilterChip(
                        selected = a.modo == Modo.CONTROL, onClick = { a = a.copy(modo = Modo.CONTROL) },
                        label = { Text("Punto de control") },
                    )
                }
                if (a.modo == Modo.CONTROL) {
                    OutlinedTextField(
                        value = a.punto, onValueChange = { a = a.copy(punto = it.uppercase().take(20)) },
                        label = { Text("Nombre del punto (PC1, KM10…)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = a.dispositivo, onValueChange = { a = a.copy(dispositivo = it.take(40)) },
                    label = { Text("Nombre de este equipo") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = a.operador, onValueChange = { a = a.copy(operador = it.take(40)) },
                    label = { Text("Operador (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Pasadas ──
        item {
            Tarjeta {
                Titulo("Pasadas y hora registrada")
                OutlinedTextField(
                    value = ventanaTxt, onValueChange = { ventanaTxt = it.filter(Char::isDigit).take(3) },
                    label = { Text("Ventana (segundos)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Si un chip deja de leerse durante este tiempo, su pasada se cierra y se envía. " +
                        "Mientras siga leyéndose, todo cuenta como una sola pasada.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Hora que se registra de cada pasada:", fontWeight = FontWeight.Medium)
                Opcion(a.regla == Regla.AUTO, "Automática", "Salida: última lectura · Control: primera lectura") {
                    a = a.copy(regla = Regla.AUTO)
                }
                Opcion(a.regla == Regla.PRIMERA, "Primera lectura", null) { a = a.copy(regla = Regla.PRIMERA) }
                Opcion(a.regla == Regla.ULTIMA, "Última lectura", null) { a = a.copy(regla = Regla.ULTIMA) }
                Opcion(a.regla == Regla.MEJOR, "Mejor señal (más cerca de la antena)", null) {
                    a = a.copy(regla = Regla.MEJOR)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Pitido al capturar", modifier = Modifier.weight(1f))
                    Switch(checked = a.sonido, onCheckedChange = { a = a.copy(sonido = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vibrar al capturar", modifier = Modifier.weight(1f))
                    Switch(checked = a.vibrar, onCheckedChange = { a = a.copy(vibrar = it) })
                }
            }
        }

        // ── Número de chip ──
        item {
            Tarjeta {
                Titulo("Número de chip")
                Text(
                    "Si NexCrono envía la lista de inscritos con el EPC o el chip de cada atleta, " +
                        "la app muestra directamente el dorsal y el nombre.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Detección automática con un chip real
                val epcUltimo = ultimaEpc
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("Detectar con un chip", fontWeight = FontWeight.Bold)
                    Text(
                        if (epcUltimo == null) "Lee un chip con la pistola y escribe aquí el número impreso en él."
                        else "Último chip leído: $epcUltimo. Escribe el número impreso en ese chip.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = numeroImpreso, onValueChange = { numeroImpreso = it.filter(Char::isDigit).take(10) },
                            label = { Text("Número impreso") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Button(
                            enabled = epcUltimo != null && numeroImpreso.isNotEmpty(),
                            onClick = {
                                val r = ChipDecoder.detectar(epcUltimo ?: "", numeroImpreso)
                                if (r == null) {
                                    mensaje = "No encontré el $numeroImpreso dentro del EPC $epcUltimo. " +
                                        "¿Seguro que es el chip que leíste último? Si el número no está en el EPC, usa la tabla EPC → chip."
                                } else {
                                    val (ini, largo, hex) = r
                                    a = a.copy(chipModo = ChipModo.SEGMENTO, segInicio = ini, segLargo = largo, segHex = hex)
                                    segInicioTxt = ini.toString(); segLargoTxt = largo.toString()
                                    mensaje = "Encontrado: posición $ini, $largo caracteres, " +
                                        (if (hex) "en hexadecimal" else "en decimal") + ". Toca GUARDAR AJUSTES para aplicarlo."
                                }
                            },
                        ) { Text("Detectar") }
                    }
                }
                Opcion(
                    a.chipModo == ChipModo.SEGMENTO, "Segmento del EPC",
                    "Un tramo del EPC. TimingSense: 00D6 01BE 0000… → 446",
                ) { a = a.copy(chipModo = ChipModo.SEGMENTO) }
                if (a.chipModo == ChipModo.SEGMENTO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = segInicioTxt, onValueChange = { segInicioTxt = it.filter(Char::isDigit).take(2) },
                            label = { Text("Desde posición") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = segLargoTxt, onValueChange = { segLargoTxt = it.filter(Char::isDigit).take(2) },
                            label = { Text("Caracteres") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Está en hexadecimal", modifier = Modifier.weight(1f))
                        Switch(checked = a.segHex, onCheckedChange = { a = a.copy(segHex = it) })
                    }
                    TextButton(onClick = {
                        a = a.copy(chipModo = ChipModo.SEGMENTO, segInicio = 5, segLargo = 4, segHex = true)
                        segInicioTxt = "5"; segLargoTxt = "4"
                    }) { Text("Usar formato TimingSense") }
                }
                Opcion(a.chipModo == ChipModo.ULTIMOS, "Últimos dígitos del EPC", "Ej.: …000001234 → 1234") {
                    a = a.copy(chipModo = ChipModo.ULTIMOS)
                }
                Opcion(a.chipModo == ChipModo.HEX_DEC, "Últimos dígitos en hexadecimal → decimal", "Ej.: …04D2 → 1234") {
                    a = a.copy(chipModo = ChipModo.HEX_DEC)
                }
                Opcion(a.chipModo == ChipModo.TABLA, "Tabla EPC → chip ($tablaTam cargados)", "Importada desde un CSV") {
                    a = a.copy(chipModo = ChipModo.TABLA)
                }
                Opcion(a.chipModo == ChipModo.COMPLETO, "EPC completo", null) {
                    a = a.copy(chipModo = ChipModo.COMPLETO)
                }
                if (a.chipModo == ChipModo.ULTIMOS || a.chipModo == ChipModo.HEX_DEC || a.chipModo == ChipModo.TABLA) {
                    OutlinedTextField(
                        value = digitosTxt, onValueChange = { digitosTxt = it.filter(Char::isDigit).take(2) },
                        label = { Text("Cantidad de dígitos") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (a.chipModo == ChipModo.TABLA) {
                    OutlinedButton(
                        onClick = { abrirTabla.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Importar tabla CSV (EPC,chip)") }
                }
                Spacer(Modifier.height(8.dp))
                val epcPrueba = ultimaEpc
                if (epcPrueba != null) {
                    val prueba = conTextos(a)
                    Text("Prueba con el último chip leído:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(epcPrueba, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(
                        "→ " + ChipDecoder.decodificar(epcPrueba, prueba, Engine.tablaParaVista()),
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = Turquesa,
                    )
                } else {
                    Text(
                        "Lee un chip con la pistola para ver aquí cómo se convierte.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Datos ──
        item {
            Tarjeta {
                Titulo("Datos y respaldo del evento")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportar(false) }, modifier = Modifier.weight(1f)) { Text("Exportar CSV") }
                    OutlinedButton(onClick = { exportar(true) }, modifier = Modifier.weight(1f)) { Text("Exportar Excel") }
                }
                OutlinedButton(
                    onClick = {
                        enFondo {
                            Engine.db.reenviarEvento(Engine.ajustes.value.eventoId)
                            Engine.marcarSucio()
                            "Las pasadas de este evento se volverán a enviar"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Marcar evento para reenviar") }
                OutlinedButton(
                    onClick = { confirmarBorrado = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rojo),
                ) { Text("Borrar pasadas de este evento") }
                Text(
                    "Los archivos quedan en Descargas/NexCrono.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Historial de sincronización ──
        item {
            Tarjeta {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Titulo("Historial (bitácora)")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { verBitacora = !verBitacora }) { Text(if (verBitacora) "Ocultar" else "Ver") }
                }
                Text(
                    "Envíos a NexCrono, descargas de inscritos, hora, licencia, eventos y accesos por WiFi. " +
                        "Queda guardado en el teléfono.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (verBitacora) {
                    val fb = remember { java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US) }
                    if (bitacora.isEmpty()) Text("Sin registros todavía.", fontSize = 13.sp)
                    bitacora.forEach { r ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(fb.format(java.util.Date(r.ts)), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(98.dp))
                            Column {
                                Text(r.tipo.uppercase(), fontSize = 10.sp, color = Turquesa, fontWeight = FontWeight.Bold)
                                Text(r.mensaje, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Manual ──
        item {
            Tarjeta {
                Titulo("Ayuda")
                Button(onClick = { Navegacion.manual = true }, modifier = Modifier.fillMaxWidth()) { Text("Abrir el manual de la aplicación") }
            }
        }

        // ── Versión ──
        item {
            Tarjeta {
                Titulo("Versión")
                Text("NexCrono Pistola ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                if (Actualizador.disponible) {
                    OutlinedButton(
                        enabled = !trabajando,
                        onClick = {
                            trabajando = true
                            scope.launch {
                                try {
                                    val r = withContext(Dispatchers.IO) { Actualizador.buscar() }
                                    if (r == null) mensaje = "Ya tienes la última versión" else Engine.actualizacion.value = r
                                } catch (e: Exception) {
                                    mensaje = "No se pudo consultar: ${e.message}"
                                }
                                trabajando = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("Buscar actualización") }
                } else {
                    Text(
                        "Actualizaciones automáticas: sin servidor configurado en esta compilación.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                "NexCrono · CodevNexus · codevnexus.tech",
                fontSize = 11.sp, color = Gris, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    // Botón guardar fijo cuando hay cambios
    if (cambios) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Bottom) {
            Button(
                onClick = { guardar() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Naranja),
            ) { Text("GUARDAR AJUSTES", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Opcion(seleccionado: Boolean, titulo: String, detalle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = seleccionado, onClick = onClick)
        Column {
            Text(titulo)
            if (detalle != null) Text(detalle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
