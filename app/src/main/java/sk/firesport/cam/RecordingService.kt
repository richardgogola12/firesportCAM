package sk.firesport.cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Popredná služba počas nahrávania – systém potom nezastaví nahrávanie,
 * keď zhasne obrazovka alebo príde hovor.
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RecordingService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nahrávanie", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Firesport Cam nahráva")
            .setContentText("Ťukni pre návrat do kamery")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}

/**
 * Vlastný "životný cyklus" pre kameru – môže bežať, aj keď je aktivita
 * na pozadí (počas nahrávania).
 */
class CameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.DESTROYED
    }
}
