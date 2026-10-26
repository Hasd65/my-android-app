package ai.syntx.btbridge.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground-сервис часов. Держит BLE GATT-сервер и датчики.
 *
 * ПОЧЕМУ FOREGROUND: без него Wear OS усыпит приложение через несколько минут,
 * и реклама GATT прекратится. Тип connectedDevice — единственный подходящий.
 *
 * ЖИЗНЕННЫЙ ЦИКЛ: START_STICKY, чтобы система подняла сервис после убийства.
 * Восстановление состояния — из WatchPrefs, не из статики (процесс может умереть).
 */
class WatchService : Service() {

    companion object {
        private const val TAG = "BtBridgeWatchSvc"
        private const val CH_FG = "bridge_fg"
        private const val CH_MSG = "bridge_msg"
        private const val FG_ID = 4001
        private const val MSG_BASE = 5000

        private const val A_START = "ai.syntx.btbridge.watch.START"
        private const val A_STOP = "ai.syntx.btbridge.watch.STOP"

        @Volatile var running = false
        @Volatile var status = "остановлен"

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java).setAction(A_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WatchService::class.java).setAction(A_STOP))
        }
    }

    private lateinit var prefs: WatchPrefs
    private lateinit var history: HistoryStore
    private var ble: BleServer? = null
    private var sensors: SensorCollector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val msgId = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        prefs = WatchPrefs(this)
        history = HistoryStore(this)
        createChannels()
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

        startForeground(FG_ID, buildStatusNotification("запуск…"))

        if (ble == null) {
            ble = BleServer(this) { id, app, title, text, at ->
                onIncoming(id, app, title, text, at)
            }.also { b ->
                b.linkListener = { connected ->
                    status = if (connected) "подключено" else "ожидание"
                    notifyStatus()
                }
            }
            val ok = ble?.start() == true
            status = if (ok) "ожидание" else "ошибка BLE"
            Log.i(TAG, "BLE start=$ok, adv=${ble?.isAdvertising}")
        }

        if (prefs.shareHealth && sensors == null) {
            sensors = SensorCollector(this) { kind, value, unit ->
                ble?.sendHealth(kind, value, unit)
            }
            sensors?.start()
        }

        acquireWakeLock()
        running = true
        notifyStatus()
        return START_STICKY
    }

    private fun onIncoming(id: Long, app: String, title: String, text: String, at: Long) {
        history.add(HistoryStore.Item(id, app, title, text, at), prefs.historyLimit)

        if (prefs.vibrate) VibratorHelper.buzz(this)
        if (prefs.wakeScreen) WakeActivity.launch(this)
        if (prefs.showCards) showMessageCard(id, app, title, text)
    }

    private fun showMessageCard(id: Long, app: String, title: String, text: String) {
        val body = if (title.isNotBlank()) "$title\n$text" else text
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_msg)
            .setContentTitle(app.ifBlank { "Сообщение" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(MSG_BASE + (id % 1000).toInt(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "нет разрешения на уведомления", e)
        }
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_FG)
            .setSmallIcon(R.drawable.ic_msg)
            .setContentTitle("BT Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyStatus() {
        val name = ble?.connectedName
        val text = if (name != null) "подключено: $name" else status
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(FG_ID, buildStatusNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notifyStatus", e)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannels() {
        val fg = NotificationChannel(
            CH_FG, "Мост (фон)", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        val msg = NotificationChannel(
            CH_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(prefs.vibrate)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(fg, msg))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtBridge::ble")
            .apply {
                setReferenceCounted(false)
                try {
                    acquire(60 * 60 * 1000L)
                } catch (e: Exception) {
                    Log.w(TAG, "wakelock", e)
                }
            }
    }

    private fun shutdown() {
        running = false
        sensors?.stop()
        sensors = null
        ble?.stop()
        ble = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release wakelock", e)
        }
        wakeLock = null
        status = "остановлен"
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
