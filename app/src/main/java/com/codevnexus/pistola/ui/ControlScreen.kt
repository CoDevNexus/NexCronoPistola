package com.codevnexus.pistola.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Inscrito
import com.codevnexus.pistola.core.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quién falta por pasar por este punto (en la salida: quién no se ha leído en el corral). */
@Composable
fun ControlScreen() {
    val scope = rememberCoroutineScope()
    val control by Engine.control.collectAsState()
    val ajustes by Engine.ajustes.collectAsState()
    var filtro by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var marcar by remember { mutableStateOf<Inscrito?>(null) }

    mensaje?.let {
        AlertDialog(
            onDismissRequest = { mensaje = null },
            confirmButton = { TextButton(onClick = { mensaje = null }) { Text("OK") } },
            text = { Text(it) },
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

    val lista = remember(control, filtro) {
        val f = filtro.trim().lowercase()
        if (f.isEmpty()) control.faltan
        else control.faltan.filter {
            it.dorsal.lowercase().contains(f) || it.nombre.lowercase().contains(f) || it.categoria.lowercase().contains(f)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Tarjeta {
                Titulo(ajustes.nombrePunto + " · " + ajustes.eventoNombre.ifBlank { "evento" })
                if (control.total == 0) {
                    Text(
                        if (ajustes.eventoLocal) "Este es un evento local sin lista de inscritos. " +
                            "Vincula con NexCrono y elige un evento para ver quién falta."
                        else "Todavía no hay inscritos descargados para este evento.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Cifra(control.pasaron.toString(), "Pasaron", Turquesa, Modifier.weight(1f))
                        Cifra(control.faltan.size.toString(), "Faltan", Naranja, Modifier.weight(1f))
                        Cifra(control.desconocidos.toString(), "No inscritos", if (control.desconocidos > 0) Rojo else Gris, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (control.total == 0) 0f else control.pasaron.toFloat() / control.total },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Turquesa, trackColor = MaterialTheme.colorScheme.background,
                    )
                }
                if (!ajustes.eventoLocal && ajustes.vinculado) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !cargando,
                        onClick = {
                            cargando = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    try { Uploader.descargarInscritos() } catch (e: Exception) { "Error: ${e.message}" }
                                }
                                cargando = false
                                mensaje = r
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (cargando) "Descargando…" else "Actualizar inscritos desde NexCrono") }
                }
            }
        }

        if (control.total > 0) {
            item {
                OutlinedTextField(
                    value = filtro, onValueChange = { filtro = it },
                    label = { Text("Buscar dorsal, nombre o categoría") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    if (control.faltan.isEmpty()) "¡Pasaron todos!" else "Faltan por pasar (toca para registrar a mano)",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(lista, key = { it.dorsal }) { i ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .clickable { marcar = i }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(i.dorsal, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(80.dp))
                    Column(Modifier.weight(1f)) {
                        Text(i.nombre, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(i.categoria, i.extra).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i.chip.isNotBlank()) Text("chip ${i.chip}", fontSize = 11.sp, color = Gris)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
