package com.codevnexus.pistola.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Estado
import com.codevnexus.pistola.core.Modo
import com.codevnexus.pistola.core.Pasada
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
    val servidor by Engine.servidor.collectAsState()
    val ajustes by Engine.ajustes.collectAsState()

    val pm = ctx.getSystemService(PowerManager::class.java)
    val sinRestriccion = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // Encabezado: punto + estado de pistola y servidor
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NexCrono Pistola", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Punto(if (servidor.ok) Turquesa else if (ajustes.servidor.isBlank()) Gris else Amarillo)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (ajustes.servidor.isBlank()) "Sin NexCrono" else if (servidor.ok) "NexCrono OK" else "NexCrono sin enlace",
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

        // Último chip, en grande
        item {
            val ultimo = recientes.firstOrNull()
            Tarjeta {
                Text("ÚLTIMO CHIP", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    ultimo?.chip ?: "—",
                    fontSize = 76.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(), maxLines = 1,
                )
                Text(
                    if (ultimo != null) formatoHora.format(Date(ultimo.hora)) else "Esperando lecturas…",
                    fontSize = 18.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Cifra(stats.chips.toString(), "Chips", Turquesa, Modifier.weight(1f))
                    Cifra(stats.pasadas.toString(), "Pasadas", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    Cifra(
                        stats.pendientes.toString(), "Por enviar",
                        if (stats.pendientes > 0) Amarillo else Gris, Modifier.weight(1f),
                    )
                }
            }
        }

        // Botón principal
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
                    Text(if (leyendo) "DETENER LECTURA" else "INICIAR LECTURA", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                if (conexion.comandos) "También puedes leer apretando el gatillo de la pistola."
                else "Esta pistola lee con su propio gatillo.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (ajustes.modo == Modo.SALIDA) {
                OutlinedButton(
                    onClick = { Engine.cerrarTodas() },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { Text("Cerrar salida ahora (fijar última lectura de todos)") }
            }
            }
        }

        item { Titulo("Pasadas recientes") }

        if (recientes.isEmpty()) {
            item {
                Text(
                    "Aquí aparecerá cada chip capturado con su hora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
            }
        }
        items(recientes, key = { it.id }) { FilaPasada(it) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

fun colorBateria(b: Int): Color = when {
    b <= 15 -> Rojo
    b <= 35 -> Amarillo
    else -> Turquesa
}

@Composable
private fun FilaPasada(p: Pasada) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.chip, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(110.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(formatoHora.format(Date(p.hora)), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Text(
                buildString {
                    append(p.lecturas).append(" lect.")
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
