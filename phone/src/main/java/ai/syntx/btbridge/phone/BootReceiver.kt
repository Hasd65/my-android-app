package ai.syntx.btbridge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск после перезагрузки телефона, если включён в настройках. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(ctx).autoStart) BridgeService.start(ctx)
    }
}
