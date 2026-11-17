package com.codevnexus.pistola.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.ChipDecoder
import com.codevnexus.pistola.core.ChipModo
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Exportador
import com.codevnexus.pistola.core.Modo
import com.codevnexus.pistola.core.Regla
import com.codevnexus.pistola.core.Uploader
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
    val servidor by Engine.servidor.collectAsState()

    var a by remember(guardado) { mutableStateOf(guardado) }
    var digitosTxt by remember(guardado) { mutableStateOf(guardado.chipDigitos.toString()) }
    var ventanaTxt by remember(guardado) { mutableStateOf(guardado.ventanaSeg.toString()) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var confirmarBorrado by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }

    val cambios = a != guardado ||
        digitosTxt != guardado.chipDigitos.toString() || ventanaTxt != guardado.ventanaSeg.toString()

    fun guardar() {
        val final = a.copy(
            chipDigitos = digitosTxt.toIntOrNull()?.coerceIn(1, 24) ?: a.chipDigitos,
            ventanaSeg = ventanaTxt.toIntOrNull()?.coerceIn(1, 600) ?: a.ventanaSeg,
        )
        Engine.guardarAjustes(final)
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
            title = { Text("¿Borrar todas las pasadas?") },
            text = { Text("Se eliminan del teléfono. Exporta un respaldo antes si lo necesitas.") },
            confirmButton = {
                TextButton(onClick = { Engine.borrarTodo(); confirmarBorrado = false }) { Text("Borrar", color = Rojo) }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ── Captura ──
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
                Text(
                    "NexCrono recibe también la primera y la última lectura, por si prefiere aplicar su propia regla.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Pitido al capturar un chip", modifier = Modifier.weight(1f))
                    Switch(checked = a.sonido, onCheckedChange = { a = a.copy(sonido = it) })
                }
            }
        }

        // ── Número de chip ──
        item {
            Tarjeta {
                Titulo("Número de chip")
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
                if (a.chipModo != ChipModo.COMPLETO) {
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
                    val prueba = a.copy(chipDigitos = digitosTxt.toIntOrNull() ?: a.chipDigitos)
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

        // ── NexCrono ──
        item {
            Tarjeta {
                Titulo("Conexión con NexCrono")
                OutlinedTextField(
                    value = a.servidor, onValueChange = { a = a.copy(servidor = it.trim()) },
                    label = { Text("Dirección (ej. http://192.168.1.10:8080)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = a.token, onValueChange = { a = a.copy(token = it.trim()) },
                    label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Enviar automáticamente", modifier = Modifier.weight(1f))
                    Switch(checked = a.autoEnvio, onCheckedChange = { a = a.copy(autoEnvio = it) })
                }
                Text(servidor.mensaje, fontSize = 12.sp, color = if (servidor.ok) Turquesa else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !trabajando && !cambios,
                        onClick = { enFondo { Uploader.sincronizarHora() } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Probar / sincronizar hora") }
                    OutlinedButton(
                        enabled = !trabajando && !cambios,
                        onClick = { enFondo { Uploader.enviarPendientes() } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Enviar ahora") }
                }
                if (cambios) {
                    Text("Guarda los ajustes para probar la conexión.", fontSize = 12.sp, color = Amarillo)
                }
            }
        }

        // ── Datos ──
        item {
            Tarjeta {
                Titulo("Datos y respaldo")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportar(false) }, modifier = Modifier.weight(1f)) { Text("Exportar CSV") }
                    OutlinedButton(onClick = { exportar(true) }, modifier = Modifier.weight(1f)) { Text("Exportar Excel") }
                }
                OutlinedButton(
                    onClick = { enFondo { Engine.db.reenviarTodo(); Engine.marcarSucio(); "Todas las pasadas se volverán a enviar" } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Marcar todo para reenviar") }
                OutlinedButton(
                    onClick = { confirmarBorrado = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rojo),
                ) { Text("Borrar todas las pasadas") }
                Text(
                    "Los archivos quedan en Descargas/NexCrono.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
