package com.codevnexus.pistola.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Estado
import com.codevnexus.pistola.core.Modo
import com.codevnexus.pistola.core.Pasada
import com.codevnexus.pistola.core.TipoAviso
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val formatoHora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun LecturaScreen(irAPistola: () -> Unit) {
    val ctx = LocalContext.current
    val conexion by Engine.conexion.collectAsState()
    val bateria by Engine.bateria.collectAsState()
    val leyendo by Engine.leyendo.collectAsState()
    val recientes by Engine.recientes.collectAsState()
    val stats by Engine.stats.collectAsState()
    val control by Engine.control.collectAsState()
    val servidor by Engine.servidor.collectAsState()
    val ajustes by Engine.ajustes.collectAsState()
    val aviso by Engine.aviso.collectAsState()

    var manual by remember { mutableStateOf(false) }

    // destello de color con cada captura
    var destello by remember { mutableStateOf(false) }
    LaunchedEffect(aviso?.ts) {
        if (aviso != null && System.currentTimeMillis() - aviso!!.ts < 2000) {
            destello = true
            delay(700)
            destello = false
        }
    }
    val colorAviso = when (aviso?.tipo) {
        TipoAviso.DESCONOCIDO -> Rojo
        TipoAviso.MANUAL -> Naranja
        else -> Turquesa
    }
    val fondo by animateColorAsState(
        if (destello) colorAviso.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(250), label = "destello",
    )

    val pm = ctx.getSystemService(PowerManager::class.java)
    val sinRestriccion = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    if (manual) DialogoManual(onCerrar = { manual = false })

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // Encabezado: evento, punto y estados
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(ajustes.empresa, ajustes.eventoNombre).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "NexCrono Pistola" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ajustes.nombrePunto,
                        fontSize = 28.sp, fontWeight = FontWeight.Black,
                        color = if (ajustes.modo == Modo.SALIDA) Naranja else Turquesa,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Punto(
                            when (conexion.estado) {
                                Estado.CONECTADO -> Turquesa
                                Estado.DESCONECTADO -> Rojo
                                else -> Amarillo
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (conexion.estado == Estado.DESCONECTADO) "Sin pistola" else conexion.nombre,
                            fontSize = 13.sp, maxLines = 1,
                        )
                    }
                    if (bateria != null) Text("Batería $bateria%", fontSize = 12.sp, color = colorBateria(bateria!!))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Punto(if (servidor.ok) Turquesa else if (!ajustes.vinculado) Gris else Amarillo)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (!ajustes.vinculado) "Sin NexCrono" else if (servidor.ok) "NexCrono OK" else "NexCrono sin enlace",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (conexion.estado != Estado.CONECTADO) {
            item {
                Tarjeta {
                    Text(conexion.mensaje, fontWeight = FontWeight.Medium)
                    if (conexion.estado == Estado.DESCONECTADO) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = irAPistola, modifier = Modifier.fillMaxWidth()) { Text("Conectar pistola") }
                    }
                }
            }
        }

        if (!sinRestriccion) {
            item {
                Tarjeta {
                    Text(
                        "Para seguir leyendo con la pantalla bloqueada, permite que la app funcione sin restricción de batería.",
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:" + ctx.packageName))
                            )
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Permitir") }
                }
            }
        }

        // Última captura, en grande
        item {
            val ultimo = recientes.firstOrNull()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = fondo),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (ultimo?.dorsal != null) "DORSAL" else "CHIP",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (ultimo != null && ultimo.dorsal == null && control.total > 0) Etiqueta("NO INSCRITO", Rojo)
                        if (ultimo != null && ultimo.origen != "vivo") Etiqueta(ultimo.origen.uppercase(), Naranja)
                    }
                    val grande = ultimo?.numero ?: "—"
                    Text(
                        grande,
                        fontSize = tamanoNumero(grande), fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    if (ultimo?.nombre != null) {
                        Text(
                            ultimo.nombre ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (!ultimo.categoria.isNullOrBlank()) {
                            Text(
                                ultimo.categoria ?: "", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text(
                        if (ultimo != null) formatoHora.format(Date(ultimo.hora)) else "Esperando lecturas…",
                        fontSize = 18.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.background)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        if (control.total > 0) {
                            Cifra("${control.pasaron}/${control.total}", "Pasaron", Turquesa, Modifier.weight(1f))
                            Cifra(
                                control.faltan.size.toString(), "Faltan",
                                if (control.faltan.isEmpty()) Gris else Naranja, Modifier.weight(1f),
                            )
                        } else {
                            Cifra(stats.chips.toString(), "Chips", Turquesa, Modifier.weight(1f))
                            Cifra(stats.pasadas.toString(), "Pasadas", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        }
                        Cifra(
                            stats.pendientes.toString(), "Por enviar",
                            if (stats.pendientes > 0) Amarillo else Gris, Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Botones principales
        item {
            Column {
                if (conexion.comandos) {
                    Button(
                        onClick = { if (leyendo) Engine.detenerLectura() else Engine.iniciarLectura() },
                        enabled = conexion.estado == Estado.CONECTADO,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (leyendo) Rojo else Turquesa),
                    ) {
                        Text(
                            if (leyendo) "DETENER LECTURA" else "INICIAR LECTURA",
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { manual = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("Dorsal manual")
                    }
                    if (ajustes.modo == Modo.SALIDA) {
                        OutlinedButton(onClick = { Engine.cerrarTodas() }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("Cerrar salida")
                        }
                    }
                }
                Text(
                    if (conexion.comandos) "También puedes leer apretando el gatillo de la pistola."
                    else "Esta pistola lee con su propio gatillo.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        item { Titulo("Pasadas recientes") }

        if (recientes.isEmpty()) {
            item {
                Text(
                    "Aquí aparecerá cada competidor capturado con su hora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
            }
        }
        items(recientes, key = { it.id }) { FilaPasada(it, control.total > 0) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Achica la letra del número grande según su largo para que siempre quepa en una línea. */
fun tamanoNumero(t: String) = when {
    t.length <= 5 -> 76.sp
    t.length <= 7 -> 58.sp
    t.length <= 10 -> 42.sp
    t.length <= 16 -> 28.sp
    else -> 20.sp
}

fun colorBateria(b: Int): Color = when {
    b <= 15 -> Rojo
    b <= 35 -> Amarillo
    else -> Turquesa
}

@Composable
private fun DialogoManual(onCerrar: () -> Unit) {
    var dorsal by remember { mutableStateOf("") }
    var ultimo by remember { mutableStateOf<String?>(null) }
    fun registrar() {
        val d = dorsal.trim()
        if (d.isEmpty()) return
        val i = Engine.buscarPorDorsal(d)
        Engine.registrarManual(d)
        ultimo = if (i != null) "✔ $d · ${i.nombre}" else "✔ $d (no está en la lista de inscritos)"
        dorsal = ""
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Dorsal manual") },
        text = {
            Column {
                Text(
                    "Para chips que no leen. Queda registrado con la hora actual y marcado como manual.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dorsal,
                    onValueChange = { dorsal = it.filter { c -> c.isLetterOrDigit() }.take(10) },
                    label = { Text("Dorsal") }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { registrar() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val i = Engine.buscarPorDorsal(dorsal.trim())
                if (i != null) Text("${i.nombre} · ${i.categoria}", color = Turquesa, fontSize = 14.sp)
                ultimo?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { registrar() }) { Text("Registrar", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

@Composable
private fun FilaPasada(p: Pasada, hayInscritos: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            p.numero, fontSize = when { p.numero.length <= 5 -> 26.sp; p.numero.length <= 8 -> 18.sp; else -> 12.sp },
            fontWeight = FontWeight.Black, modifier = Modifier.width(96.dp), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(1f)) {
            if (p.nombre != null) {
                Text(p.nombre ?: "", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (hayInscritos && p.dorsal == null) {
                Text("No inscrito · chip ${p.chip}", fontSize = 13.sp, color = Rojo, maxLines = 1)
            }
            Text(formatoHora.format(Date(p.hora)), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(
                buildString {
                    if (p.origen == "vivo") append(p.lecturas).append(" lect.") else append(p.origen)
                    p.rssi?.let { append(" · ").append(it).append(" dBm") }
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !p.cerrada -> Etiqueta("EN CURSO", Naranja)
            p.enviado -> Etiqueta("ENVIADO", Turquesa)
            else -> Etiqueta("PENDIENTE", Amarillo)
        }
    }
}
