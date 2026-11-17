package com.codevnexus.pistola

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.codevnexus.pistola.core.Engine
import com.codevnexus.pistola.ui.AjustesScreen
import com.codevnexus.pistola.ui.LecturaScreen
import com.codevnexus.pistola.ui.PistolaScreen
import com.codevnexus.pistola.ui.PistolaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        setContent { PistolaTheme { App() } }
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
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) { permisos.launch(permisosNecesarios()) }

    var pestana by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = pestana == 0, onClick = { pestana = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Lectura") },
                )
                NavigationBarItem(
                    selected = pestana == 1, onClick = { pestana = 1 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) }, label = { Text("Pistola") },
                )
                NavigationBarItem(
                    selected = pestana == 2, onClick = { pestana = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Ajustes") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (pestana) {
                0 -> LecturaScreen(irAPistola = { pestana = 1 })
                1 -> PistolaScreen()
                else -> AjustesScreen()
            }
        }
    }
}
