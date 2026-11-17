package com.codevnexus.pistola.ui

import android.provider.OpenableColumns
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Exportador
import com.codevnexus.pistola.core.Importador
import com.codevnexus.pistola.core.Inscrito
import com.codevnexus.pistola.core.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Vista { FALTAN, DETECTADOS, NO_INSCRITOS }

/** KPI del punto y listas de detectados / no detectados (eventos locales y de NexCrono). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val control by Engine.control.collectAsState()
    val ajustes by Engine.ajustes.collectAsState()
    var vista by remember { mutableStateOf(Vista.FALTAN) }
    var filtro by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var marcar by remember { mutableStateOf<Inscrito?>(null) }
    var porCargar by remember { mutableStateOf<Importador.Resultado?>(null) }
    val fh = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val abrirArchivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val nombre = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("No se pudo abrir el archivo")
                    if (bytes.size > 20 * 1024 * 1024) throw Exception("El archivo es demasiado grande (máx. 20 MB)")
                    Importador.leer(bytes, nombre)
                }
                porCargar = r
            } catch (e: Exception) {
                mensaje = e.message ?: "No se pudo leer el archivo"
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
    porCargar?.let { r ->
        AlertDialog(
            onDismissRequest = { porCargar = null },
            title = { Text("Cargar ${r.inscritos.size} participantes") },
            text = {
                Column {
                    Text("Evento: ${ajustes.eventoNombre}")
                    Text("Columnas reconocidas: " + r.columnas.joinToString(", "), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.omitidas > 0) Text("${r.omitidas} filas sin dorsal se omitieron.", fontSize = 13.sp, color = Amarillo)
                    val ej = r.inscritos.first()
                    Text("Ejemplo: ${ej.dorsal} · ${ej.nombre} · ${ej.categoria}", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    if (control.total > 0) Text(
                        "El evento ya tiene ${control.total} participantes. «Reemplazar» borra la lista actual; " +
                            "«Agregar» suma y actualiza por dorsal.", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lista = r.inscritos
                    porCargar = null
                    scope.launch { mensaje = withContext(Dispatchers.IO) { Engine.cargarParticipantes(lista, reemplazar = true) } }
                }) { Text(if (control.total > 0) "Reemplazar" else "Cargar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    if (control.total > 0) TextButton(onClick = {
                        val lista = r.inscritos
                        porCargar = null
                        scope.launch { mensaje = withContext(Dispatchers.IO) { Engine.cargarParticipantes(lista, reemplazar = false) } }
                    }) { Text("Agregar") }
                    TextButton(onClick = { porCargar = null }) { Text("Cancelar") }
                }
            },
        )
    }
    marcar?.let { i ->
        AlertDialog(
            onDismissRequest = { marcar = null },
            title = { Text("Registrar a mano") },
            text = { Text("¿Registrar el dorsal ${i.dorsal} (${i.nombre}) con la hora actual en ${ajustes.nombrePunto}?") },
            confirmButton = {
                TextButton(onClick = { Engine.registrarManual(i.dorsal); marcar = null }) {
                    Text("Registrar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { marcar = null }) { Text("Cancelar") } },
        )
    }

    val q = filtro.trim().lowercase()
    fun coincide(i: Inscrito) = q.isEmpty() || i.dorsal.lowercase().contains(q) ||
        i.nombre.lowercase().contains(q) || i.categoria.lowercase().contains(q)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(ajustes.nombrePunto, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text(ajustes.eventoNombre, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BotonInfo("Control")
            }
        }

        // ── KPI ──
        item {
            Tarjeta {
                if (control.total == 0) {
                    Text(
                        "No hay participantes en este evento. Cárgalos desde un Excel o CSV" +
                            (if (!ajustes.eventoLocal && ajustes.vinculado) " o descárgalos de NexCrono." else "."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Cifra(control.total.toString(), "Participantes", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Cifra(control.pasaron.toString(), "Detectados", Turquesa, Modifier.weight(1f))
                        Cifra(control.faltan.size.toString(), "No detectados", if (control.faltan.isEmpty()) Gris else Naranja, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { control.porcentaje / 100f },
                            modifier = Modifier.weight(1f).height(10.dp),
                            color = Turquesa, trackColor = MaterialTheme.colorScheme.background,
                        )
                        Text("  ${control.porcentaje}%", fontWeight = FontWeight.Bold)
                    }
                    if (control.desconocidos > 0) {
                        Text("${control.desconocidos} chips leídos no están inscritos", color = Rojo, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            abrirArchivo.launch(arrayOf(
                                "text/*", "text/csv", "text/comma-separated-values",
                                Exportador.MIME_XLSX, "application/vnd.ms-excel", "application/octet-stream",
                            ))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cargar Excel / CSV") }
                    if (!ajustes.eventoLocal && ajustes.vinculado) {
                        OutlinedButton(
                            enabled = !cargando,
                            onClick = {
                                cargando = true
                                scope.launch {
                                    mensaje = withContext(Dispatchers.IO) {
                                        try { Uploader.descargarInscritos() } catch (e: Exception) { "Error: ${e.message}" }
                                    }
                                    cargando = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (cargando) "Descargando…" else "De NexCrono") }
                    }
                }
            }
        }

        if (control.total > 0 || control.desconocidos > 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(vista == Vista.FALTAN, { vista = Vista.FALTAN }, label = { Text("No detectados ${control.faltan.size}") })
                    FilterChip(vista == Vista.DETECTADOS, { vista = Vista.DETECTADOS }, label = { Text("Detectados ${control.pasaron}") })
                    if (control.desconocidos > 0) {
                        FilterChip(vista == Vista.NO_INSCRITOS, { vista = Vista.NO_INSCRITOS }, label = { Text("No inscritos ${control.desconocidos}") })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = filtro, onValueChange = { filtro = it },
                    label = { Text("Buscar dorsal, nombre o categoría") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (vista) {
                Vista.FALTAN -> {
                    val l = control.faltan.filter { coincide(it) }
                    item {
                        Text(
                            if (control.faltan.isEmpty()) "¡Todos detectados!" else "Toca un participante para registrarlo a mano.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(l, key = { "f" + it.dorsal }) { i ->
                        FilaParticipante(i, derecha = if (i.chip.isNotBlank()) "chip ${i.chip}" else "", color = Gris) { marcar = i }
                    }
                }
                Vista.DETECTADOS -> {
                    val l = control.detectados.filter { coincide(it.inscrito) }
                    items(l, key = { "d" + it.inscrito.dorsal }) { d ->
                        FilaParticipante(
                            d.inscrito,
                            derecha = fh.format(Date(d.hora)) + if (d.origen != "vivo") "\n${d.origen}" else "",
                            color = Turquesa, onClick = null,
                        )
                    }
                }
                Vista.NO_INSCRITOS -> {
                    items(control.desconocidosLista, key = { "n" + it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Exportador.chipConvertido(p), fontSize = 20.sp, fontWeight = FontWeight.Black, color = Rojo,
                                modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Column(Modifier.weight(1f)) {
                                Text(p.epc, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fh.format(Date(p.hora)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FilaParticipante(i: Inscrito, derecha: String, color: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(i.dorsal, fontSize = 22.sp, fontWeight = FontWeight.Black, color = color, modifier = Modifier.width(80.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(i.nombre.ifBlank { "—" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(i.categoria, i.extra).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (derecha.isNotBlank()) Text(derecha, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
