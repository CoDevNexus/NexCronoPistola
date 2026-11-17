package com.codevnexus.pistola.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.codevnexus.pistola.core.Engine

/**
 * Servicio en primer plano: mantiene la conexión Bluetooth, la lectura y el envío
 * aunque la pantalla esté bloqueada o la app en segundo plano.
 */
class LectorService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        val n = Engine.notificacion("Iniciando…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Engine.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(Engine.NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Engine.log("! No se pudo iniciar el segundo plano: ${e.message}")
            stopSelf()
            return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexCronoPistola:lector")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            Engine.desconectar(detenerServicio = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !Engine.reconectarUltima()) {   // reinicio del sistema sin pistola que reconectar
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACCION_DETENER = "com.codevnexus.pistola.DETENER"
    }
}
