package ai.syntx.btbridge.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Сбор данных с датчиков часов через стандартный SensorManager.
 *
 * ПОЧЕМУ НЕ Health Services: androidx.health.services требует наличия
 * соответствующего сервиса на часах, и его доступность на OnePlus Watch 3
 * не проверена. SensorManager есть всегда и даёт те же датчики на уровне железа.
 *
 * ЧТО СНИМАЕТСЯ:
 *  - TYPE_HEART_RATE — пульс (bpm), пассивно, без запроса пользователя.
 *  - TYPE_STEP_COUNTER — шаги с загрузки, поэтому передаётся дельта.
 *
 * ЧАСТОТА: пульс не чаще раза в 60 с, шаги — при изменении более чем на 10.
 * Это компромисс: чаще — расход батареи, реже — данные неинтересны.
 */
class SensorCollector(
    private val ctx: Context,
    private val onSample: (kind: String, value: Double, unit: String) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "BtBridgeSensors"
        private const val HR_INTERVAL_MS = 60_000L
        private const val STEPS_DELTA_MIN = 10
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var hr: Sensor? = null
    private var steps: Sensor? = null

    private var lastHrAt = 0L
    private var lastStepBase = -1f
    private var lastStepSent = -1f

    fun start() {
        val manager = sm ?: return

        hr = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        steps = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        hr?.let {
            // SENSOR_DELAY_NORMAL: экономия батареи, пульс не требует высокой частоты.
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "heart rate sensor: ${it.name}")
        } ?: Log.w(TAG, "датчик пульса недоступен")

        steps?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "step counter: ${it.name}")
        } ?: Log.w(TAG, "счётчик шагов недоступен")
    }

    fun stop() {
        try {
            sm?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "unregister", e)
        }
        handler.removeCallbacksAndMessages(null)
        hr = null
        steps = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull() ?: return
                // 0 — нет валидного измерения, не отправляем.
                if (bpm <= 0f) return
                val now = System.currentTimeMillis()
                if (now - lastHrAt < HR_INTERVAL_MS) return
                lastHrAt = now
                onSample("hr", bpm.toDouble(), "bpm")
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                if (lastStepBase < 0f) {
                    // Первое событие: запоминаем базу, дельту не отправляем,
                    // иначе после перезапуска уйдёт «весь день разом».
                    lastStepBase = total
                    lastStepSent = total
                    return
                }
                lastStepSent = total
                val delta = total - lastStepBase
                if (delta >= STEPS_DELTA_MIN) {
                    lastStepBase = total
                    onSample("steps", delta.toDouble(), "steps")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
