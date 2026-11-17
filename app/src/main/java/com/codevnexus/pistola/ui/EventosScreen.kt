package com.codevnexus.pistola.ui

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Evento
import com.codevnexus.pistola.core.EventoResumen
import com.codevnexus.pistola.core.Exportador
import com.codevnexus.pistola.core.ServidorWeb
import com.codevnexus.pistola.core.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Historial de eventos guardados en el teléfono (SQLite): abrir, crear, editar, borrar y compartir. */
@Composable
fun EventosScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ajustes by Engine.ajustes.collectAsState()
    val stats by Engine.stats.collectAsState()
    val web by ServidorWeb.estado.collectAsState()
    var refrescar by remember { mutableIntStateOf(0) }
    val lista by produceState(emptyList<EventoResumen>(), stats, ajustes.eventoId, refrescar) {
        value = withContext(Dispatchers.IO) { Engine.db.eventosResumen() }
    }

    var editando by remember { mutableStateOf<Evento?>(null) }      // evento en edición (id vacío = nuevo)
    var borrar by remember { mutableStateOf<Evento?>(null) }
    var compartir by remember { mutableStateOf<Evento?>(null) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var deNexCrono by remember { mutableStateOf<List<Evento>?>(null) }
    var trabajando by remember { mutableStateOf(false) }

    fun enviarArchivo(e: Evento, excel: Boolean, participantes: Boolean) {
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { Exportador.exportarEvento(ctx, e, excel, participantes) }
                val i = Intent(Intent.ACTION_SEND)
                    .setType(if (excel) Exportador.MIME_XLSX else Exportador.MIME_CSV)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "Compartir"))
            } catch (ex: Exception) {
                mensaje = ex.message ?: "No se pudo crear el archivo"
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
    editando?.let { e -> DialogoEvento(e, onCerrar = { editando = null }) { guardado, abrir ->
        scope.launch {
            withContext(Dispatchers.IO) {
                if (guardado.id.isEmpty()) {
                    Engine.crearEventoLocal(guardado.nombre, guardado.fecha, guardado.lugar, guardado.notas, abrir)
                } else {
                    Engine.guardarEvento(guardado)
                }
            }
            editando = null
            refrescar++
        }
    } }
    borrar?.let { e ->
        AlertDialog(
            onDismissRequest = { borrar = null },
            title = { Text("¿Eliminar «${e.nombre}»?") },
            text = {
                Text(
                    "Se borran del teléfono sus pasadas y su lista de participantes. Las pasadas que no se enviaron " +
                        "a NexCrono se pierden. Exporta un respaldo antes si lo necesitas."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = e.id
                    borrar = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Engine.borrarEvento(id) }
                        refrescar++
                    }
                }) { Text("Eliminar", color = Rojo) }
            },
            dismissButton = { TextButton(onClick = { borrar = null }) { Text("Cancelar") } },
        )
    }
    compartir?.let { e ->
        AlertDialog(
            onDismissRequest = { compartir = null },
            title = { Text("Compartir «${e.nombre}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("El número de chip va ya convertido.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { compartir = null; enviarArchivo(e, true, false) }, modifier = Modifier.fillMaxWidth()) { Text("Pasadas · Excel") }
                    OutlinedButton(onClick = { compartir = null; enviarArchivo(e, false, false) }, modifier = Modifier.fillMaxWidth()) { Text("Pasadas · CSV") }
                    OutlinedButton(onClick = { compartir = null; enviarArchivo(e, true, true) }, modifier = Modifier.fillMaxWidth()) { Text("Participantes y estado · Excel") }
                    OutlinedButton(onClick = { compartir = null; enviarArchivo(e, false, true) }, modifier = Modifier.fillMaxWidth()) { Text("Participantes y estado · CSV") }
                }
            },
            confirmButton = { TextButton(onClick = { compartir = null }) { Text("Cerrar") } },
        )
    }
    deNexCrono?.let { l ->
        AlertDialog(
            onDismissRequest = { deNexCrono = null },
            title = { Text("Eventos en NexCrono") },
            text = {
                Column {
                    if (l.isEmpty()) Text("NexCrono no tiene eventos disponibles.")
                    l.forEach { e ->
                        TextButton(onClick = {
                            deNexCrono = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    Engine.cambiarEvento(e)
                                    try { Uploader.descargarInscritos() } catch (ex: Exception) { "Inscritos: ${ex.message}" }
                                }
                                refrescar++
                                mensaje = "Evento abierto: ${e.nombre}\n$r"
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(e.nombre + if (e.fecha.isNotBlank()) " · ${e.fecha}" else "", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deNexCrono = null }) { Text("Cerrar") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Eventos", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                BotonInfo("Eventos")
            }
        }

        // ── Compartir por WiFi ──
        item {
            Tarjeta {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Titulo("Compartir por WiFi")
                        Text(
                            "Otro equipo en el mismo WiFi (o conectado al hotspot de este teléfono) abre la dirección " +
                                "en su navegador y descarga los archivos. No necesita internet.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = web.activo, onCheckedChange = { on ->
                        scope.launch(Dispatchers.IO) { if (on) ServidorWeb.iniciar() else ServidorWeb.detener() }
                    })
                }
                if (web.activo) {
                    Spacer(Modifier.height(8.dp))
                    Text("Dirección", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(web.url, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Turquesa)
                    Text("PIN", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(web.pin.chunked(3).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text(
                        "El PIN cambia cada vez que se enciende. Apágalo al terminar.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (web.mensaje != "Apagado") {
                    Text(web.mensaje, color = Amarillo, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        // ── Acciones ──
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        editando = Evento(
                            "", "", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), true,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("+ Nuevo evento") }
                OutlinedButton(
                    enabled = ajustes.vinculado && !trabajando,
                    onClick = {
                        trabajando = true
                        scope.launch {
                            try {
                                deNexCrono = withContext(Dispatchers.IO) { Uploader.eventos() }
                            } catch (e: Exception) {
                                mensaje = "No se pudieron traer los eventos: ${e.message}"
                            }
                            trabajando = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("De NexCrono") }
            }
        }

        item { Titulo("Historial (${lista.size})") }
        items(lista, key = { it.evento.id }) { r -> TarjetaEvento(
            r, actual = r.evento.id == ajustes.eventoId,
            onAbrir = { scope.launch { withContext(Dispatchers.IO) { Engine.cambiarEvento(r.evento) }; refrescar++ } },
            onEditar = { editando = r.evento },
            onCompartir = { compartir = r.evento },
            onBorrar = { borrar = r.evento },
        ) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TarjetaEvento(
    r: EventoResumen, actual: Boolean,
    onAbrir: () -> Unit, onEditar: () -> Unit, onCompartir: () -> Unit, onBorrar: () -> Unit,
) {
    val e = r.evento
    val f = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.nombre, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (actual) Etiqueta("ACTUAL", Turquesa)
            Spacer(Modifier.width(4.dp))
            Etiqueta(if (e.local) "LOCAL" else "NEXCRONO", if (e.local) Gris else Naranja)
        }
        val linea = listOf(e.fecha, e.lugar).filter { it.isNotBlank() }.joinToString(" · ")
        if (linea.isNotBlank()) Text(linea, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.notas.isNotBlank()) Text(e.notas, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Cifra(r.pasadas.toString(), "Pasadas", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Cifra(r.chips.toString(), "Chips", Turquesa, Modifier.weight(1f))
            Cifra(r.inscritos.toString(), "Particip.", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Cifra(r.pendientes.toString(), "Por enviar", if (r.pendientes > 0) Amarillo else Gris, Modifier.weight(1f))
        }
        r.ultima?.let {
            Text("Última lectura: ${f.format(Date(it))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (!actual) TextButton(onClick = onAbrir) { Text("Abrir") }
            TextButton(onClick = onEditar) { Text("Editar") }
            TextButton(onClick = onCompartir) { Text("Compartir") }
            TextButton(onClick = onBorrar, colors = ButtonDefaults.textButtonColors(contentColor = Rojo)) { Text("Eliminar") }
        }
    }
}

@Composable
private fun DialogoEvento(e: Evento, onCerrar: () -> Unit, onGuardar: (Evento, Boolean) -> Unit) {
    var nombre by remember { mutableStateOf(e.nombre) }
    var fecha by remember { mutableStateOf(e.fecha) }
    var lugar by remember { mutableStateOf(e.lugar) }
    var notas by remember { mutableStateOf(e.notas) }
    val nuevo = e.id.isEmpty()
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (nuevo) "Nuevo evento" else "Editar evento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(nombre, { nombre = it.take(80) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fecha, { fecha = it.take(10) }, label = { Text("Fecha (aaaa-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lugar, { lugar = it.take(80) }, label = { Text("Lugar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notas, { notas = it.take(300) }, label = { Text("Notas") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (!e.local && !nuevo) {
                    Text("Evento de NexCrono: el nombre puede volver a cambiar al sincronizar.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = nombre.isNotBlank(), onClick = {
                onGuardar(e.copy(nombre = nombre.trim(), fecha = fecha.trim(), lugar = lugar.trim(), notas = notas.trim()), true)
            }) { Text(if (nuevo) "Crear y abrir" else "Guardar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (nuevo) TextButton(enabled = nombre.isNotBlank(), onClick = {
                    onGuardar(e.copy(nombre = nombre.trim(), fecha = fecha.trim(), lugar = lugar.trim(), notas = notas.trim()), false)
                }) { Text("Solo crear") }
                TextButton(onClick = onCerrar) { Text("Cancelar") }
            }
        },
    )
}
