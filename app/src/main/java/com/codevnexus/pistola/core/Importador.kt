package com.codevnexus.pistola.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Lee la lista de participantes desde CSV o Excel (.xlsx), sin librerías externas.
 * Reconoce las columnas por su encabezado (en cualquier orden):
 *   dorsal (obligatoria) · nombre · apellidos · categoría · chip · EPC · género/sexo · club/equipo
 */
object Importador {

    class Resultado(val inscritos: List<Inscrito>, val columnas: List<String>, val omitidas: Int)

    fun leer(bytes: ByteArray, nombreArchivo: String): Resultado {
        val esXlsx = nombreArchivo.lowercase().endsWith(".xlsx") ||
            (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        val filas = if (esXlsx) leerXlsx(bytes) else leerCsv(bytes)
        return interpretar(filas)
    }

    // ───────────── Encabezados ─────────────

    private val alias = mapOf(
        "dorsal" to listOf("dorsal", "bib", "numero", "nro", "num", "no", "numerodorsal", "dorsales", "n"),
        "nombre" to listOf("nombre", "nombres", "nombrecompleto", "atleta", "participante", "corredor", "deportista"),
        "apellido" to listOf("apellido", "apellidos"),
        "categoria" to listOf("categoria", "cat", "categorias", "division"),
        "chip" to listOf("chip", "numerochip", "codigochip", "nchip", "tag"),
        "epc" to listOf("epc", "codigoepc"),
        "genero" to listOf("genero", "sexo", "gender", "rama"),
        "club" to listOf("club", "equipo", "team", "clubequipo"),
    )

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    private fun interpretar(filas: List<List<String>>): Resultado {
        // el encabezado es la primera de las 10 primeras filas que tenga una columna de dorsal
        var iEnc = -1
        var mapa: Map<String, Int> = emptyMap()
        for (i in 0 until minOf(10, filas.size)) {
            val m = HashMap<String, Int>()
            filas[i].forEachIndexed { col, txt ->
                val n = normalizar(txt)
                for ((campo, nombres) in alias) if (n in nombres && campo !in m) m[campo] = col
            }
            if ("dorsal" in m) {
                iEnc = i
                mapa = m
                break
            }
        }
        if (iEnc < 0) {
            throw IOException(
                "No encontré la columna de dorsal. La primera fila debe tener encabezados como: " +
                    "Dorsal, Nombre, Apellidos, Categoría, Chip, EPC, Género, Club."
            )
        }
        fun celda(f: List<String>, campo: String) = mapa[campo]?.let { f.getOrNull(it) }?.trim() ?: ""
        val lista = LinkedHashMap<String, Inscrito>()
        var omitidas = 0
        for (f in filas.drop(iEnc + 1)) {
            val dorsal = limpiarNumero(celda(f, "dorsal"))
            if (dorsal.isEmpty()) {
                if (f.any { it.isNotBlank() }) omitidas++
                continue
            }
            val nombre = listOf(celda(f, "nombre"), celda(f, "apellido")).filter { it.isNotBlank() }.joinToString(" ")
            lista[dorsal] = Inscrito(
                dorsal = dorsal,
                chip = limpiarNumero(celda(f, "chip")),
                epc = celda(f, "epc").uppercase().filter { it.isLetterOrDigit() },
                nombre = nombre,
                categoria = celda(f, "categoria"),
                extra = listOf(celda(f, "genero"), celda(f, "club")).filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
        if (lista.isEmpty()) throw IOException("El archivo no tiene filas con dorsal debajo del encabezado.")
        return Resultado(lista.values.toList(), mapa.keys.toList(), omitidas)
    }

    /** "12.0" → "12" (Excel guarda los números como decimales) */
    private fun limpiarNumero(s: String): String {
        val t = s.trim()
        val d = t.toDoubleOrNull()
        return if (d != null && d == Math.floor(d) && !t.contains('E', true) && d < 1e15) d.toLong().toString() else t
    }

    // ───────────── CSV ─────────────

    fun leerCsv(bytes: ByteArray): List<List<String>> {
        var texto = String(bytes, Charsets.UTF_8)
        if (texto.contains('\uFFFD')) texto = String(bytes, Charsets.ISO_8859_1)   // archivos de Excel antiguo
        texto = texto.removePrefix("\uFEFF")
        val primera = texto.lineSequence().firstOrNull() ?: ""
        val sep = listOf(';', ',', '\t').maxByOrNull { c -> primera.count { it == c } } ?: ','
        val filas = ArrayList<List<String>>()
        var fila = ArrayList<String>()
        val campo = StringBuilder()
        var comillas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                comillas && c == '"' && i + 1 < texto.length && texto[i + 1] == '"' -> { campo.append('"'); i++ }
                c == '"' -> comillas = !comillas
                !comillas && c == sep -> { fila.add(campo.toString()); campo.clear() }
                !comillas && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') i++
                    fila.add(campo.toString()); campo.clear()
                    filas.add(fila); fila = ArrayList()
                }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); filas.add(fila) }
        return filas
    }

    // ───────────── XLSX (primera hoja) ─────────────

    fun leerXlsx(bytes: ByteArray): List<List<String>> {
        val partes = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (n == "xl/sharedStrings.xml" || n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" ||
                    n.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                ) {
                    partes[n] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        val compartidas = partes["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1])
                    .joinToString("") { desescapar(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val hoja = primeraHoja(partes) ?: throw IOException("El Excel no tiene hojas")
        val filas = ArrayList<List<String>>()
        for (fila in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(hoja)) {
            val celdas = HashMap<Int, String>()
            val reCelda = Regex("<c ([^>]*?)(/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
            var siguiente = 0
            for (c in reCelda.findAll(fila.groupValues[1])) {
                val attrs = c.groupValues[1]
                val cuerpo = c.groupValues[3]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                val col = ref?.let { columna(it) } ?: siguiente
                siguiente = col + 1
                val tipo = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(cuerpo)?.groupValues?.get(1)
                val valor = when (tipo) {
                    "s" -> v?.toIntOrNull()?.let { compartidas.getOrNull(it) } ?: ""
                    "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(cuerpo)
                        .joinToString("") { desescapar(it.groupValues[1]) }
                    else -> desescapar(v ?: "")
                }
                celdas[col] = valor
            }
            val max = (celdas.keys.maxOrNull() ?: -1)
            filas.add((0..max).map { celdas[it] ?: "" })
        }
        return filas
    }

    private fun primeraHoja(partes: Map<String, String>): String? {
        // workbook.xml dice cuál es la primera hoja; rels dice en qué archivo está
        val wb = partes["xl/workbook.xml"]
        val rels = partes["xl/_rels/workbook.xml.rels"]
        if (wb != null && rels != null) {
            val rid = Regex("<sheet [^>]*r:id=\"([^\"]+)\"").find(wb)?.groupValues?.get(1)
            if (rid != null) {
                val destino = Regex("<Relationship [^>]*Id=\"${Regex.escape(rid)}\"[^>]*Target=\"([^\"]+)\"").find(rels)?.groupValues?.get(1)
                    ?: Regex("<Relationship [^>]*Target=\"([^\"]+)\"[^>]*Id=\"${Regex.escape(rid)}\"").find(rels)?.groupValues?.get(1)
                if (destino != null) {
                    val ruta = if (destino.startsWith("/")) destino.removePrefix("/") else "xl/" + destino
                    partes[ruta]?.let { return it }
                }
            }
        }
        return partes.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 999 }
            ?.let { partes[it] }
    }

    private fun columna(letras: String): Int {
        var n = 0
        for (ch in letras) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    private val entidadNumerica = Regex("&#(x[0-9A-Fa-f]+|\\d+);")

    private fun desescapar(s: String): String {
        val conNumeros = entidadNumerica.replace(s) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        return conNumeros.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
