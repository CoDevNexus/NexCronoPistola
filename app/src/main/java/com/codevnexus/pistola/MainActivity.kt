package com.codevnexus.pistola

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codevnexus.pistola.core.Actualizador
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.core.Uploader
import com.codevnexus.pistola.core.Vinculo
import com.codevnexus.pistola.ui.AjustesScreen
import com.codevnexus.pistola.ui.ControlScreen
import com.codevnexus.pistola.ui.EventosScreen
import com.codevnexus.pistola.ui.ManualScreen
import com.codevnexus.pistola.ui.Navegacion
import com.codevnexus.pistola.ui.LecturaScreen
import com.codevnexus.pistola.ui.PistolaScreen
import com.codevnexus.pistola.ui.PistolaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        leerEnlace(intent)
        setContent { PistolaTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leerEnlace(intent)
    }

    private fun leerEnlace(i: Intent?) {
        val datos = i?.data?.toString() ?: return
        Vinculo.desde(datos)?.let { Engine.vinculoPendiente.value = it }
    }
}

fun permisosNecesarios(): Array<String> {
    val l = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        l += Manifest.permission.BLUETOOTH_CONNECT
        l += Manifest.permission.BLUETOOTH_SCAN
    } else {
        l += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    return l.toTypedArray()
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val vinculo by Engine.vinculoPendiente.collectAsState()
    val actualizacion by Engine.actualizacion.collectAsState()
    var resultado by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    var progreso by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        permisos.launch(permisosNecesarios())
        if (Actualizador.disponible) {
            Engine.actualizacion.value = withContext(Dispatchers.IO) {
                try { Actualizador.buscar() } catch (_: Exception) { null }
            }
        }
    }

    // ── Vincular con NexCrono (QR o enlace) ──
    vinculo?.let { v ->
        AlertDialog(
            onDismissRequest = { if (!trabajando) Engine.vinculoPendiente.value = null },
            title = { Text("Vincular con NexCrono") },
            text = {
                Column {
                    Text("Servidor: ${v.url}")
                    v.eventoNombre?.let { Text("Evento: $it") }
                    v.punto?.let { Text("Punto: $it") }
                    v.modo?.let { Text("Modo: $it") }
                    if (trabajando) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !trabajando, onClick = {
                    trabajando = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try { Uploader.vincular(v) } catch (e: Exception) { "No se pudo vincular: ${e.message}" }
                        }
                        trabajando = false
                        Engine.vinculoPendiente.value = null
                        resultado = r
                    }
                }) { Text("Vincular", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(enabled = !trabajando, onClick = { Engine.vinculoPendiente.value = null }) { Text("Cancelar") }
            },
        )
    }

    // ── Actualización disponible ──
    actualizacion?.let { a ->
        AlertDialog(
            onDismissRequest = { if (!a.obligatoria && progreso < 0) Engine.actualizacion.value = null },
            title = { Text("Nueva versión ${a.versionName}") },
            text = {
                Column {
                    if (a.notas.isNotBlank()) Text(a.notas)
                    if (progreso >= 0) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progreso / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Descargando… $progreso%")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = progreso < 0, onClick = {
                    if (!Actualizador.puedeInstalar(ctx)) {
                        resultado = "Permite que NexCrono Pistola instale apps (se abrirá Ajustes) y vuelve a tocar Actualizar."
                        Actualizador.pedirPermisoInstalar(ctx)
                    } else {
                        progreso = 0
                        scope.launch {
                            val error = withContext(Dispatchers.IO) {
                                try {
                                    Actualizador.descargarEInstalar(ctx, a) { p -> progreso = p }
                                    null
                                } catch (e: Exception) {
                                    e.message ?: "Error al actualizar"
                                }
                            }
                            progreso = -1
                            if (error != null) resultado = error else Engine.actualizacion.value = null
                        }
                    }
                }) { Text("Actualizar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                if (!a.obligatoria) {
                    TextButton(enabled = progreso < 0, onClick = { Engine.actualizacion.value = null }) { Text("Después") }
                }
            },
        )
    }

    resultado?.let {
        AlertDialog(
            onDismissRequest = { resultado = null },
            confirmButton = { TextButton(onClick = { resultado = null }) { Text("OK") } },
            text = { Text(it) },
        )
    }

    if (Navegacion.manual) {
        ManualScreen(onCerrar = { Navegacion.manual = false })
        return
    }

    val pestana = Navegacion.pestana
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = pestana == 0, onClick = { Navegacion.pestana = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Lectura") },
                )
                NavigationBarItem(
                    selected = pestana == 1, onClick = { Navegacion.pestana = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) }, label = { Text("Control") },
                )
                NavigationBarItem(
                    selected = pestana == 2, onClick = { Navegacion.pestana = 2 },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) }, label = { Text("Eventos") },
                )
                NavigationBarItem(
                    selected = pestana == 3, onClick = { Navegacion.pestana = 3 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) }, label = { Text("Pistola") },
                )
                NavigationBarItem(
                    selected = pestana == 4, onClick = { Navegacion.pestana = 4 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Ajustes") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (pestana) {
                0 -> LecturaScreen(irAPistola = { Navegacion.pestana = 3 })
                1 -> ControlScreen()
                2 -> EventosScreen()
                3 -> PistolaScreen()
                else -> AjustesScreen()
            }
        }
    }
}
