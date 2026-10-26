package ai.syntx.btbridge.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.syntx.btbridge.common.Msg

/**
 * Foreground-сервис телефона: держит GATT-клиент и связывает его с перехватчиком.
 *
 * ПОЧЕМУ FOREGROUND С connectedDevice: без этого ColorOS убьёт процесс через
 * несколько минут, и связь с часами разорвётся. Тип connectedDevice — корректный
 * для работы с носимым устройством и не требует дополнительных обоснований.
 *
 * START_STICKY: система поднимет сервис после убийства. Состояние настроек
 * читается из Prefs, а не из статики.
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "BtBridgePhoneSvc"
        private const val CH_ID = "bridge_fg_phone"
        private const val FG_ID = 1001

        private const val A_START = "ai.syntx.btbridge.phone.START"
        private const val A_STOP = "ai.syntx.btbridge.phone.STOP"

        @Volatile var running = false
        @Volatile var status = "остановлен"
        @Volatile var connected = false
        @Volatile var deviceName: String? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BridgeService::class.java).setAction(A_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(A_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private var client: BleClient? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(FG_ID, buildNotification("запуск…"))

        if (client == null) {
            client = BleClient(
                ctx = this,
                onStatus = { s ->
                    status = s
                    connected = client?.connected == true
                    deviceName = client?.deviceName
                    if (connected) prefs.lastConnectedAt = System.currentTimeMillis()
                    updateNotification()
                },
                onHealth = { kind, value, unit, at ->
                    HealthStore(this).add(kind, value, unit, at)
                }
            ).also { it.start() }
        }

        // Подключаем перехватчик: sink — единственный канал передачи.
        NotificationRelayService.prefs = prefs
        NotificationRelayService.sink = { order, app, title, text, at, pkg ->
            val json = Msg.notify(order, app, title, text, at, pkg)
            client?.enqueue(json)
            prefs.sentCount = prefs.sentCount + 1
        }

        running = true
        updateNotification()
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun updateNotification() {
        val text = if (connected) "подключено: ${deviceName ?: "часы"}" else status
        try {
            getSystemService(NotificationManager::class.java).notify(FG_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification", e)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CH_ID, "Мост к часам", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun shutdown() {
        running = false
        client?.stop()
        client = null
        NotificationRelayService.sink = null
        status = "остановлен"
        connected = false
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
