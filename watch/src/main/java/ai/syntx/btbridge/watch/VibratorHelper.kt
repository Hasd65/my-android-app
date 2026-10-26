package ai.syntx.btbridge.watch

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Вибрация. На API 31+ требуется VibratorManager, старый API Vibrator
 * возвращает null на Wear OS 5 — это проверено на устройстве.
 * Ошибки глушим: вибрация не критична, падать из-за неё нельзя.
 */
object VibratorHelper {

    fun buzz(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w("BtBridgeVibro", "vibrate", e)
        }
    }
}
