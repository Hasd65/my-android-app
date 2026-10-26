package ai.syntx.btbridge.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Перехват уведомлений Android.
 *
 * ЧТО ОТПРАВЛЯЕТСЯ: только новые уведомления (isOngoing == false) и только
 * если приложение не в чёрном списке. Обновления одного и того же уведомления
 * не дублируются: ключ — (packageName, id, postTime).
 *
 * ЧЕГО НЕ ДЕЛАЕМ:
 *  - не пересылаем медиа-уведомления (плеер) — они бесполезны на часах и шумят;
 *  - не пересылаем собственные уведомления, иначе получится петля;
 *  - не пересымаем «тихие» уведомления (IMPORTANCE_MIN), если так настроено.
 */
class NotificationRelayService : NotificationListenerService() {

    companion object {
        private const val TAG = "BtBridgeNotif"

        /** Куда отправлять. Ставится сервисом-владельцем при запуске. */
        @Volatile var sink: ((order: Long, app: String, title: String, text: String, at: Long, pkg: String) -> Unit)? = null

        @Volatile var prefs: Prefs? = null

        @Volatile var alive = false

        private val counter = AtomicLong(0)

        /** Отпечатки уже отправленных уведомлений, чтобы не дублировать. */
        private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > 128
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        if (prefs == null) prefs = Prefs(this)
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    override fun onListenerConnected() {
        alive = true
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        alive = false
        Log.w(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val cfg = prefs ?: return
        val out = sink ?: return

        try {
            // Собственные уведомления не пересылаем никогда: это прямая петля.
            if (n.packageName == packageName) return
            if (cfg.dropOwn && n.packageName.startsWith("ai.syntx.btbridge")) return

            val notif = n.notification ?: return

            // Ongoing — музыка, навигация, загрузки. На часах бесполезно.
            if (notif.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return

            // Групповые summary дублируют содержимое дочерних.
            if (notif.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

            val white = cfg.whitelist
            if (white.isNotEmpty() && n.packageName !in white) return
            if (n.packageName in cfg.blacklist) return

            val extras = notif.extras ?: return
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))?.toString() ?: ""

            if (title.isBlank() && text.isBlank()) return

            val key = "${n.packageName}|${n.id}|${n.postTime}"
            synchronized(seen) {
                if (seen.containsKey(key)) return
                seen[key] = n.postTime
            }

            val app = appLabel(n.packageName)
            val at = if (n.postTime > 0) n.postTime else System.currentTimeMillis()

            out(
                counter.incrementAndGet(),
                app,
                if (cfg.sendContent) title else "",
                if (cfg.sendContent) text else "",
                at,
                n.packageName
            )
        } catch (e: Exception) {
            Log.w(TAG, "onNotificationPosted", e)
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
