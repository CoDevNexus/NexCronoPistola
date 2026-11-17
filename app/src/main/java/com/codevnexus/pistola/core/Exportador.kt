package com.codevnexus.pistola.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archivos de un evento: pasadas (CSV / Excel) y participantes con su estado.
 * Los usan «Exportar», «Compartir» y el servidor WiFi.
 * El número de chip va SIEMPRE ya convertido con la configuración actual de «Número de chip».
 */
object Exportador {

    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_CSV = "text/csv"

    private val encabezados = arrayOf(
        "Número", "Dorsal", "Nombre", "Categoría", "Número de chip", "EPC", "Hora", "Primera lectura",
        "Última lectura", "RSSI (dBm)", "Lecturas", "Punto", "Modo", "Dispositivo", "Origen",
        "Hora sincronizada", "Enviado", "Evento", "ID",
    )

    /** Número de chip convertido con la configuración actual (las pasadas manuales guardan el suyo). */
    fun chipConvertido(p: Pasada): String =
        if (p.epc.isNotBlank() && (p.origen == "vivo" || p.origen.startsWith("memoria"))) Engine.decodificar(p.epc) else p.chip

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun filasPasadas(evento: Evento): List<Array<Any?>> {
        val f = fmt()
        return Engine.db.todas(evento.id).map { p ->
            val chip = chipConvertido(p)
            arrayOf(
                p.dorsal ?: chip, p.dorsal ?: "", p.nombre ?: "", p.categoria ?: "", chip, p.epc,
                f.format(Date(p.hora)), f.format(Date(p.primera)), f.format(Date(p.ultima)),
                p.rssi, p.lecturas, p.punto, p.modo, p.dispositivo, p.origen,
                if (p.sincronizado) "SI" else "NO", if (p.enviado) "SI" else "NO", evento.nombre, p.id,
            )
        }
    }

    /**
     * Todas las lecturas (marcaciones masivas): una fila por cada vez que la pistola leyó un chip.
     * Pensada para cargar a mano en NexCrono si hiciera falta: fecha y hora separadas, número ya convertido.
     */
    private val encabezadosLecturas = arrayOf(
        "Fecha", "Hora", "Número", "Dorsal", "Nombre", "Número de chip", "EPC", "RSSI (dBm)",
        "Punto", "Modo", "Origen", "Dispositivo", "Marca de tiempo (ms)", "ID pasada",
    )

    private fun filasLecturas(evento: Evento): List<Array<Any?>> {
        Engine.guardarBufferLecturas()
        val fd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fh = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val cache = HashMap<String, String>()
        return Engine.db.lecturasExportables(evento.id).map { x ->
            val l = x.l
            val chip = if (l.epc.isNotBlank() && (l.origen == "vivo" || l.origen.startsWith("memoria"))) {
                cache.getOrPut(l.epc) { Engine.decodificar(l.epc) }
            } else x.dorsal ?: ""
            val d = Date(l.ts)
            arrayOf<Any?>(
                fd.format(d), fh.format(d), x.dorsal ?: chip, x.dorsal ?: "", x.nombre ?: "", chip, l.epc, l.rssi,
                l.punto, x.modo ?: "", l.origen, l.dispositivo, l.ts, l.pasada,
            )
        }
    }

    private val encabezadosParticipantes = arrayOf(
        "Dorsal", "Nombre", "Categoría", "Número de chip", "EPC", "Estado", "Hora", "Origen", "Extra",
    )

    private fun filasParticipantes(evento: Evento): List<Array<Any?>> {
        val f = fmt()
        val pasaron = Engine.db.primeraPorDorsal(evento.id)
        val filas = Engine.db.inscritos(evento.id)
            .sortedWith(compareBy({ it.dorsal.length }, { it.dorsal }))
            .map { i ->
                val p = pasaron[i.dorsal]
                arrayOf<Any?>(
                    i.dorsal, i.nombre, i.categoria, i.chip, i.epc,
                    if (p != null) "DETECTADO" else "NO DETECTADO",
                    p?.let { f.format(Date(it.first)) } ?: "", p?.second ?: "", i.extra,
                )
            }.toMutableList()
        // chips leídos que no están inscritos
        for (p in Engine.db.noInscritos(evento.id)) {
            filas += arrayOf<Any?>("", "", "", chipConvertido(p), p.epc, "NO INSCRITO", f.format(Date(p.hora)), p.origen, "")
        }
        return filas
    }

    private fun csv(encabezados: Array<String>, filas: List<Array<Any?>>): ByteArray {
        val sb = StringBuilder()
        sb.append(encabezados.joinToString(",") { celda(it) }).append("\r\n")
        for (f in filas) sb.append(f.joinToString(",") { celda(it) }).append("\r\n")
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))   // BOM: Excel abre bien las tildes
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun celda(v: Any?): String {
        val s = v?.toString() ?: ""
        // texto que Excel tomaría por fórmula
        val seguro = if (s.isNotEmpty() && s[0] in "=+-@" && s.toDoubleOrNull() == null) "'$s" else s
        return if (seguro.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + seguro.replace("\"", "\"\"") + "\"" else seguro
    }

    private fun xlsx(hoja: String, encabezados: Array<String>, filas: List<Array<Any?>>): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, hoja, encabezados, filas)
        return out.toByteArray()
    }

    // ───────────── Contenido en memoria (servidor WiFi y archivos) ─────────────

    fun pasadasCsv(e: Evento) = csv(encabezados, filasPasadas(e))
    fun lecturasCsv(e: Evento) = csv(encabezadosLecturas, filasLecturas(e))

    /** Excel del evento: hoja «Pasadas» y hoja «Lecturas» con todas las marcaciones. */
    fun pasadasXlsx(e: Evento): ByteArray {
        Engine.guardarBufferLecturas()
        val out = ByteArrayOutputStream()
        val hojas = java.util.ArrayList<XlsxWriter.Hoja>()
        hojas.add(XlsxWriter.Hoja("Pasadas", encabezados, filasPasadas(e)))
        hojas.add(XlsxWriter.Hoja("Lecturas", encabezadosLecturas, filasLecturas(e)))
        XlsxWriter.writeHojas(out, hojas)
        return out.toByteArray()
    }
    fun participantesCsv(e: Evento) = csv(encabezadosParticipantes, filasParticipantes(e))
    fun participantesXlsx(e: Evento) = xlsx("Participantes", encabezadosParticipantes, filasParticipantes(e))

    fun nombreArchivo(e: Evento, tipo: String, ext: String): String {
        val limpio = e.nombre.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "evento" }
        return "${tipo}_${limpio}_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".$ext"
    }

    // ───────────── Guardar en Descargas ─────────────

    /** Evento actual. @return Uri del archivo creado en Descargas/NexCrono */
    fun exportar(ctx: Context, excel: Boolean): Uri {
        val e = Engine.eventoActual() ?: throw IOException("No hay evento actual")
        return exportarEvento(ctx, e, excel, TIPO_PASADAS)
    }

    const val TIPO_PASADAS = "pasadas"
    const val TIPO_LECTURAS = "lecturas"
    const val TIPO_PARTICIPANTES = "participantes"

    /** @param tipo TIPO_PASADAS (el Excel incluye la hoja Lecturas), TIPO_LECTURAS o TIPO_PARTICIPANTES */
    fun exportarEvento(ctx: Context, e: Evento, excel: Boolean, tipo: String): Uri {
        when (tipo) {
            TIPO_PARTICIPANTES -> if (Engine.db.inscritos(e.id).isEmpty() && Engine.db.noInscritos(e.id).isEmpty()) {
                throw IOException("El evento no tiene participantes cargados")
            }
            TIPO_LECTURAS -> {
                Engine.guardarBufferLecturas()
                if (Engine.db.contarLecturas(e.id) == 0) throw IOException("El evento no tiene lecturas guardadas")
            }
            else -> if (Engine.db.estadisticas(e.id).pasadas == 0) throw IOException("El evento no tiene pasadas")
        }
        val datos = when (tipo) {
            TIPO_PARTICIPANTES -> if (excel) participantesXlsx(e) else participantesCsv(e)
            TIPO_LECTURAS -> if (excel) xlsx("Lecturas", encabezadosLecturas, filasLecturas(e)) else lecturasCsv(e)
            else -> if (excel) pasadasXlsx(e) else pasadasCsv(e)
        }
        val nombre = nombreArchivo(e, tipo, if (excel) "xlsx" else "csv")
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, if (excel) MIME_XLSX else MIME_CSV)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NexCrono")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("No se pudo crear el archivo")
        ctx.contentResolver.openOutputStream(uri).use { os ->
            if (os == null) throw IOException("No se pudo escribir el archivo")
            os.write(datos)
        }
        return uri
    }

    /** Lee una tabla EPC → chip desde CSV (separador , ; o tabulador, en cualquier orden de columnas). */
    fun leerTabla(ctx: Context, uri: Uri): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val hex = Regex("^[0-9A-Fa-f]{8,64}$")
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lineas ->
            for (linea in lineas) {
                val cols = linea.split(',', ';', '\t').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                if (cols.size < 2) continue
                val iEpc = cols.indexOfFirst { hex.matches(it) && it.length >= 12 }
                if (iEpc < 0) continue
                val chip = cols.filterIndexed { i, _ -> i != iEpc }.first()
                m[cols[iEpc].uppercase()] = chip
            }
        } ?: throw IOException("No se pudo abrir el archivo")
        return m
    }
}
