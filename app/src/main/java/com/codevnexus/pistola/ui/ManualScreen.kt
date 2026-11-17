package com.codevnexus.pistola.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codevnexus.pistola.BuildConfig

/** Un bloque del manual ya interpretado. */
private sealed class Bloque {
    data class Titulo(val texto: String) : Bloque()
    data class Seccion(val texto: String) : Bloque()
    data class Sub(val texto: String) : Bloque()
    data class Parrafo(val texto: String) : Bloque()
    data class Vineta(val texto: String, val marca: String) : Bloque()
    data class Nota(val texto: String) : Bloque()
    data class Codigo(val texto: String) : Bloque()
    object Espacio : Bloque()
}

private fun interpretar(md: String): List<Bloque> {
    val out = ArrayList<Bloque>()
    var enCodigo = false
    val codigo = StringBuilder()
    for (raw in md.lines()) {
        val l = raw.trimEnd()
        if (l.trim().startsWith("```")) {
            if (enCodigo) { out += Bloque.Codigo(codigo.toString().trimEnd()); codigo.clear() }
            enCodigo = !enCodigo
            continue
        }
        if (enCodigo) { codigo.append(l).append('\n'); continue }
        val t = l.trim()
        out += when {
            t.isEmpty() -> Bloque.Espacio
            t.startsWith("### ") -> Bloque.Sub(t.removePrefix("### "))
            t.startsWith("## ") -> Bloque.Seccion(t.removePrefix("## "))
            t.startsWith("# ") -> Bloque.Titulo(t.removePrefix("# "))
            t.startsWith("> ") -> Bloque.Nota(t.removePrefix("> "))
            t.startsWith("- ") || t.startsWith("* ") -> Bloque.Vineta(t.drop(2), if (l.startsWith("  ")) "◦" else "•")
            Regex("^\\d+\\. ").containsMatchIn(t) -> Bloque.Vineta(t.substringAfter(". "), t.substringBefore(". ") + ".")
            else -> Bloque.Parrafo(t)
        }
    }
    // quitar espacios repetidos
    return out.filterIndexed { i, b -> !(b is Bloque.Espacio && i > 0 && out[i - 1] is Bloque.Espacio) }
}

/** **negrita** y `código` dentro de una línea */
private fun enLinea(t: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < t.length) {
        when {
            t.startsWith("**", i) && t.indexOf("**", i + 2) > 0 -> {
                val fin = t.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(i + 2, fin)) }
                i = fin + 2
            }
            t[i] == '`' && t.indexOf('`', i + 1) > 0 -> {
                val fin = t.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Naranja)) { append(t.substring(i + 1, fin)) }
                i = fin + 1
            }
            else -> { append(t[i]); i++ }
        }
    }
}

@Composable
fun ManualScreen(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onCerrar)
    val bloques = remember {
        val md = try {
            ctx.assets.open("manual.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "# Manual\n\nNo se pudo abrir el manual: ${e.message}"
        }
        interpretar(md)
    }
    var buscar by remember { mutableStateOf("") }
    var verIndice by remember { mutableStateOf(true) }
    val estadoLista = rememberLazyListState()
    val secciones = remember(bloques) {
        bloques.mapIndexedNotNull { i, b -> if (b is Bloque.Seccion) i to b.texto else null }
    }
    val visibles = remember(bloques, buscar) {
        val q = buscar.trim().lowercase()
        if (q.isEmpty()) bloques.withIndex().toList()
        else {
            // mostrar las secciones que contienen el texto buscado, completas
            val res = ArrayList<IndexedValue<Bloque>>()
            var seccionActual = ArrayList<IndexedValue<Bloque>>()
            var coincide = false
            fun cerrar() { if (coincide) res += seccionActual; seccionActual = ArrayList(); coincide = false }
            for (iv in bloques.withIndex()) {
                if (iv.value is Bloque.Seccion) cerrar()
                seccionActual += iv
                if (texto(iv.value).lowercase().contains(q)) coincide = true
            }
            cerrar()
            res
        }
    }

    LaunchedEffect(Navegacion.seccionManual) {
        val s = Navegacion.seccionManual ?: return@LaunchedEffect
        val i = bloques.indexOfFirst { it is Bloque.Seccion && it.texto.contains(s, ignoreCase = true) }
        if (i >= 0) {
            verIndice = false
            buscar = ""
            kotlinx.coroutines.delay(50)
            estadoLista.scrollToItem(i)
        }
        Navegacion.seccionManual = null
    }

    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCerrar) { Text("← Volver") }
            Text("Manual", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { verIndice = !verIndice }) { Text(if (verIndice) "Ocultar índice" else "Índice") }
        }
        OutlinedTextField(
            value = buscar, onValueChange = { buscar = it }, singleLine = true,
            placeholder = { Text("Buscar en el manual…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = estadoLista) {
            if (verIndice && buscar.isBlank()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(12.dp)
                    ) {
                        Text("ÍNDICE", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        secciones.forEach { (i, titulo) ->
                            Text(
                                titulo, modifier = Modifier.fillMaxWidth().clickable {
                                    verIndice = false
                                    Navegacion.seccionManual = titulo
                                }.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            itemsIndexed(visibles) { _, iv -> Dibujar(iv.value) }
            item {
                Text(
                    "NexCrono Pistola ${BuildConfig.VERSION_NAME} · CodevNexus · codevnexus.tech",
                    fontSize = 11.sp, color = Gris, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}
}

private fun texto(b: Bloque): String = when (b) {
    is Bloque.Titulo -> b.texto
    is Bloque.Seccion -> b.texto
    is Bloque.Sub -> b.texto
    is Bloque.Parrafo -> b.texto
    is Bloque.Vineta -> b.texto
    is Bloque.Nota -> b.texto
    is Bloque.Codigo -> b.texto
    Bloque.Espacio -> ""
}

@Composable
private fun Dibujar(b: Bloque) {
    when (b) {
        is Bloque.Titulo -> Text(b.texto, fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        is Bloque.Seccion -> Text(b.texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Turquesa, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
        is Bloque.Sub -> Text(b.texto, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        is Bloque.Parrafo -> Text(enLinea(b.texto), fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onBackground)
        is Bloque.Vineta -> Row(Modifier.padding(vertical = 2.dp)) {
            Text(b.marca, color = Turquesa, modifier = Modifier.width(26.dp), fontWeight = FontWeight.Bold)
            Text(enLinea(b.texto), fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onBackground)
        }
        is Bloque.Nota -> Text(
            enLinea(b.texto), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(Naranja.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        )
        is Bloque.Codigo -> Text(
            b.texto, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(10.dp),
        )
        Bloque.Espacio -> Spacer(Modifier.height(6.dp))
    }
}
