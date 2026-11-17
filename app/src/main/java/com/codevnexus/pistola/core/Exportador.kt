package com.codevnexus.pistola.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Respaldo en CSV o Excel dentro de Descargas. */
object Exportador {

    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_CSV = "text/csv"

    private val encabezados = arrayOf(
        "Chip", "EPC", "Hora", "Primera lectura", "Última lectura", "RSSI (dBm)", "Lecturas",
        "Punto", "Modo", "Dispositivo", "Origen", "Hora sincronizada", "Enviado", "ID"
    )

    private fun fila(p: Pasada, f: SimpleDateFormat): Array<Any?> = arrayOf(
        p.chip, p.epc, f.format(Date(p.hora)), f.format(Date(p.primera)), f.format(Date(p.ultima)),
        p.rssi, p.lecturas, p.punto, p.modo, p.dispositivo, p.origen,
        if (p.sincronizado) "SI" else "NO", if (p.enviado) "SI" else "NO", p.id
    )

    /** @return Uri del archivo creado en Descargas */
    fun exportar(ctx: Context, excel: Boolean): Uri {
        val datos = Engine.db.todas()
        if (datos.isEmpty()) throw IOException("No hay pasadas para exportar")
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val punto = Engine.ajustes.value.nombrePunto.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val nombre = "pistola_${punto}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            if (excel) ".xlsx" else ".csv"

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, if (excel) MIME_XLSX else MIME_CSV)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NexCrono")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("No se pudo crear el archivo")
        ctx.contentResolver.openOutputStream(uri).use { os ->
            if (os == null) throw IOException("No se pudo escribir el archivo")
            if (excel) {
                XlsxWriter.write(os, "Pasadas", encabezados, datos.map { fila(it, f) })
            } else {
                val sb = StringBuilder()
                sb.append(encabezados.joinToString(",")).append("\r\n")
                for (p in datos) {
                    sb.append(fila(p, f).joinToString(",") { csv(it) }).append("\r\n")
                }
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM para Excel
                os.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        }
        return uri
    }

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
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
