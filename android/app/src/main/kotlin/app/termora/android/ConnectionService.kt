package app.termora.android

import android.app.*
import android.content.Intent
import android.os.*
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Explicitly started while the app is visible. Closing the notification releases every connection.
 */
class ConnectionService : Service() {
    companion object {
        val sessions = ConcurrentHashMap<String, Closeable>()

        fun closeAll() {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") {
            closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("connections", "远程连接", NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ConnectionService::class.java).setAction("close"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, "connections")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Termora 远程连接")
                .setContentText("保持终端和端口转发连接")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "断开全部", stop).build())
                .setOngoing(true)
                .build()
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }
}
