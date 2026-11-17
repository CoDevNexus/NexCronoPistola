package com.codevnexus.pistola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Turquesa = Color(0xFF00C2A8)
val Naranja = Color(0xFFFF8A3D)
val Rojo = Color(0xFFFF5370)
val Amarillo = Color(0xFFFFC857)
val Gris = Color(0xFF9FB2B8)

private val Esquema = darkColorScheme(
    primary = Turquesa,
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF004D43),
    onPrimaryContainer = Color(0xFFB8FFF1),
    secondary = Naranja,
    onSecondary = Color(0xFF2B1300),
    background = Color(0xFF0B1114),
    onBackground = Color(0xFFE6EEF0),
    surface = Color(0xFF0B1114),
    onSurface = Color(0xFFE6EEF0),
    surfaceVariant = Color(0xFF1A252A),
    onSurfaceVariant = Gris,
    surfaceContainer = Color(0xFF131C20),
    surfaceContainerHigh = Color(0xFF1A252A),
    error = Rojo,
)

@Composable
fun PistolaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, content = content)
}

@Composable
fun Tarjeta(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Titulo(texto: String) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun Punto(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun Etiqueta(texto: String, color: Color) {
    Text(
        texto,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun FilaDato(clave: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clave, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(valor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Cifra(valor: String, etiqueta: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, fontSize = 26.sp, fontWeight = FontWeight.Black, color = color)
        Text(etiqueta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Estado de navegación compartido por toda la app. */
object Navegacion {
    var pestana by mutableIntStateOf(0)
    var manual by mutableStateOf(false)
    /** Sección del manual a la que saltar al abrirlo (título «## …»), o null. */
    var seccionManual by mutableStateOf<String?>(null)
}

/** Botón «i»: abre el manual de la aplicación (opcionalmente en una sección). */
@Composable
fun BotonInfo(seccion: String? = null) {
    IconButton(onClick = {
        Navegacion.seccionManual = seccion
        Navegacion.manual = true
    }) {
        Icon(Icons.Filled.Info, contentDescription = "Manual", tint = MaterialTheme.colorScheme.primary)
    }
}
