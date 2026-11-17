package com.codevnexus.pistola.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.codevnexus.pistola.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualización desde el hosting de CodevNexus (carpeta servidor-actualizaciones/).
 * La URL se fija al compilar (BuildConfig.UPDATE_URL): no se puede cambiar desde el teléfono.
 * Doble verificación: SHA-256 publicado por el servidor + Android exige que el APK nuevo
 * esté firmado con la misma llave que el instalado.
 */
object Actualizador {

    const val APP = "pistola"

    val disponible: Boolean get() = BuildConfig.UPDATE_URL.isNotBlank()

    /** @return la actualización si hay una versión más nueva, null si no. */
    fun buscar(): Actualizacion? {
        if (!disponible) return null
        val url = BuildConfig.UPDATE_URL.trimEnd('/') + "/api/version.php?app=" + APP +
            "&v=" + BuildConfig.VERSION_CODE
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            if (c.responseCode !in 200..299) throw Exception("Servidor de actualizaciones respondió ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = Actualizacion(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName"),
                url = o.getString("url"),
                sha256 = o.getString("sha256").lowercase(),
                notas = o.optString("notas"),
                obligatoria = o.optBoolean("obligatoria", false),
            )
            if (!a.url.startsWith("https://") && !BuildConfig.UPDATE_URL.startsWith("http://")) {
                throw Exception("La descarga debe ser por HTTPS")
            }
            return if (a.versionCode > BuildConfig.VERSION_CODE) a else null
        } finally {
            c.disconnect()
        }
    }

    fun puedeInstalar(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun pedirPermisoInstalar(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Descarga, verifica el SHA-256 e inicia la instalación (Android pide confirmación). */
    fun descargarEInstalar(ctx: Context, a: Actualizacion, progreso: (Int) -> Unit) {
        val destino = File(ctx.cacheDir, "actualizacion.apk")
        val c = URL(a.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            if (c.responseCode !in 200..299) throw Exception("Descarga falló (${c.responseCode})")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { inp ->
                destino.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var leido = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        leido += n
                        if (total > 0) progreso((leido * 100 / total).toInt())
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (hash != a.sha256) {
                destino.delete()
                throw Exception("El archivo descargado no coincide con la firma publicada. Instalación cancelada.")
            }
        } finally {
            c.disconnect()
        }
        instalar(ctx, destino)
    }

    private fun instalar(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { inp ->
                s.openWrite("app.apk", 0, apk.length()).use { out ->
                    inp.copyTo(out)
                    s.fsync(out)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstalacionReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }
}

/** Recibe el resultado de la instalación; si Android pide confirmación, abre la pantalla del sistema. */
class InstalacionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmar?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Engine.log("Actualización instalada")
            else -> Engine.log(
                "! Instalación no completada ($estado): " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
            )
        }
    }
}
