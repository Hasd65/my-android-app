package ai.syntx.btbridge.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск после перезагрузки часов.
 * Проверяем настройку: пользователь мог отключить автозапуск.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (WatchPrefs(ctx).autoStart) WatchService.start(ctx)
    }
}
